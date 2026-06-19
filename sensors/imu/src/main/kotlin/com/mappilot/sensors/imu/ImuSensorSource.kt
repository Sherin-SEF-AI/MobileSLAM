package com.mappilot.sensors.imu

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.mappilot.core.common.buffer.RingBuffer
import com.mappilot.core.common.bus.EventBus
import com.mappilot.core.common.capture.CaptureSource
import com.mappilot.core.common.config.ConfigProvider
import com.mappilot.core.common.log.Log
import com.mappilot.core.common.log.Streams
import com.mappilot.core.common.time.TimeSource
import com.mappilot.core.model.ImuSample
import com.mappilot.core.model.MapPilotEvent
import com.mappilot.core.model.RotationSample
import com.mappilot.core.model.StreamIds
import com.mappilot.core.model.TimestampSource
import com.mappilot.core.timesync.SyncEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-rate IMU capture over [SensorManager], on a dedicated [HandlerThread] so
 * sensor callbacks never run on the main thread and never touch disk/DB.
 *
 * Each sample is normalized to the unified `elapsedRealtimeNanos` base by the
 * [SyncEngine], pushed into a per-source ring buffer (the writer thread drains
 * it in Phase 2), and batched onto the [EventBus] for live observers (HUD).
 *
 * Android sensor event timestamps are documented to be in the
 * `elapsedRealtimeNanos` base since the sensor HAL, so streams register as
 * [TimestampSource.REALTIME]; the SyncEngine still validates and flags any
 * stream that misbehaves rather than trusting blindly.
 */
@Singleton
class ImuSensorSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configProvider: ConfigProvider,
    private val syncEngine: SyncEngine,
    private val eventBus: EventBus,
    private val timeSource: TimeSource,
) : CaptureSource {

    override val name: String = "imu"

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    /** Ring buffer of tri-axis + rotation samples for the recording writer (Phase 2). */
    val sampleBuffer = RingBuffer<ImuSample>(IMU_BUFFER_CAPACITY)
    val rotationBuffer = RingBuffer<RotationSample>(ROTATION_BUFFER_CAPACITY)

    @Volatile
    override var isRunning: Boolean = false
        private set

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var listener: Listener? = null
    private val batch = ArrayList<ImuSample>(BATCH_SOFT_LIMIT)

    /** Whether this device supports the SensorDirectChannel fast path (opt-in). */
    fun supportsDirectChannel(): Boolean =
        SensorDirectChannelSource(context, syncEngine, eventBus).capability().supported

    /** Sensors actually present on this device, for capability reporting. */
    fun availableStreams(): List<String> = ImuStream.entries
        .filter { sensorManager.getDefaultSensor(it.sensorType) != null }
        .map { it.streamId } +
        (if (sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null)
            listOf(StreamIds.IMU_ROTATION) else emptyList())

    @Synchronized
    override fun start() {
        if (isRunning) return
        val targetHz = configProvider.current().imuTargetHz
        val samplingPeriodUs = (1_000_000 / targetHz).coerceAtLeast(0)

        val ht = HandlerThread("imu-sensor", Thread.MAX_PRIORITY).also { it.start() }
        val h = Handler(ht.looper)
        val l = Listener()

        var registered = 0
        for (stream in ImuStream.entries) {
            val sensor = sensorManager.getDefaultSensor(stream.sensorType) ?: continue
            syncEngine.registerStream(stream.streamId, TimestampSource.REALTIME, targetHz.toDouble())
            // maxReportLatencyUs = 0 → no batching in the HAL; lowest latency.
            sensorManager.registerListener(l, sensor, samplingPeriodUs, 0, h)
            registered++
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { rot ->
            syncEngine.registerStream(StreamIds.IMU_ROTATION, TimestampSource.REALTIME, targetHz.toDouble())
            sensorManager.registerListener(l, rot, samplingPeriodUs, 0, h)
            registered++
        }

        thread = ht
        handler = h
        listener = l
        isRunning = true
        h.post(flushRunnable)
        Log.i(Streams.IMU, "IMU started: $registered sensors @ requested ${targetHz}Hz ($samplingPeriodUs us)")
    }

    @Synchronized
    override fun stop() {
        if (!isRunning) return
        isRunning = false
        listener?.let { sensorManager.unregisterListener(it) }
        handler?.removeCallbacks(flushRunnable)
        flushBatch()
        thread?.quitSafely()
        thread = null
        handler = null
        listener = null
        Log.i(Streams.IMU, "IMU stopped")
    }

    private val flushRunnable = object : Runnable {
        override fun run() {
            flushBatch()
            if (isRunning) handler?.postDelayed(this, FLUSH_INTERVAL_MS)
        }
    }

    private fun flushBatch() {
        if (batch.isEmpty()) return
        val copy = ArrayList(batch)
        batch.clear()
        eventBus.emit(MapPilotEvent.ImuBatch(copy.last().timestampNs, copy))
    }

    /** Runs entirely on the IMU HandlerThread. */
    private inner class Listener : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val arrival = timeSource.elapsedRealtimeNanos()

            if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                val ts = syncEngine.recordSample(StreamIds.IMU_ROTATION, event.timestamp, arrival)
                // values: [x, y, z, (w), (heading accuracy)]. w derived if absent.
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val w = if (event.values.size >= 4) event.values[3] else {
                    val t = 1f - (x * x + y * y + z * z)
                    if (t > 0f) Math.sqrt(t.toDouble()).toFloat() else 0f
                }
                val sample = RotationSample(ts, x, y, z, w, event.accuracy)
                if (!rotationBuffer.offer(sample)) {
                    syncEngine.recordDropped(StreamIds.IMU_ROTATION, 1)
                }
                eventBus.emit(MapPilotEvent.RotationUpdate(ts, sample))
                return
            }

            val stream = ImuStream.forSensorType(event.sensor.type) ?: return
            val ts = syncEngine.recordSample(stream.streamId, event.timestamp, arrival)
            val sample = ImuSample(
                channel = stream.channel,
                timestampNs = ts,
                x = event.values[0],
                y = event.values[1],
                z = event.values[2],
                accuracy = event.accuracy,
            )
            if (!sampleBuffer.offer(sample)) {
                syncEngine.recordDropped(stream.streamId, 1)
            }
            batch.add(sample)
            if (batch.size >= BATCH_SOFT_LIMIT) flushBatch()
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
            Log.d(Streams.IMU, "Accuracy changed: ${sensor.stringType} -> $accuracy")
        }
    }

    private companion object {
        const val IMU_BUFFER_CAPACITY = 8192
        const val ROTATION_BUFFER_CAPACITY = 4096
        const val BATCH_SOFT_LIMIT = 32
        const val FLUSH_INTERVAL_MS = 50L
    }
}
