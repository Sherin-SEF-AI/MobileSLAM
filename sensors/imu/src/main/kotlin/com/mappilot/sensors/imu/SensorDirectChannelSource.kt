package com.mappilot.sensors.imu

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorDirectChannel
import android.hardware.SensorManager
import android.os.MemoryFile
import com.mappilot.core.common.bus.EventBus
import com.mappilot.core.common.capture.CaptureSource
import com.mappilot.core.common.log.Log
import com.mappilot.core.common.log.Streams
import com.mappilot.core.model.ImuChannel
import com.mappilot.core.model.ImuSample
import com.mappilot.core.model.MapPilotEvent
import com.mappilot.core.model.StreamIds
import com.mappilot.core.model.TimestampSource
import com.mappilot.core.timesync.SyncEngine
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Optional high-rate IMU path via [SensorDirectChannel] over a [MemoryFile]
 * (TYPE_MEMORY_FILE). Where the device advertises a direct-report rate, this
 * bypasses the per-event callback path for accelerometer and gyroscope.
 *
 * It is **opt-in** and capability-gated: [start] returns having reported
 * UNAVAILABLE (and stays not-running) on any device that does not truly support
 * it, so the caller falls back to [ImuSensorSource]. We never silently pretend
 * the fast path is active.
 */
internal class SensorDirectChannelSource(
    private val context: Context,
    private val syncEngine: SyncEngine,
    private val eventBus: EventBus,
) : CaptureSource {

    override val name: String = "imu-direct"

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    @Volatile
    override var isRunning: Boolean = false
        private set

    private var memoryFile: MemoryFile? = null
    private var channel: SensorDirectChannel? = null
    private var pollLoop: Thread? = null

    data class Capability(
        val supported: Boolean,
        val accelRateLevel: Int,
        val gyroRateLevel: Int,
        val reason: String,
    )

    /** Probe whether a usable direct channel exists for accel + gyro. */
    fun capability(): Capability {
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (accel == null || gyro == null) {
            return Capability(false, 0, 0, "accelerometer or gyroscope missing")
        }
        val accelRate = accel.highestDirectReportRateLevel
        val gyroRate = gyro.highestDirectReportRateLevel
        val typeOk = accel.isDirectChannelTypeSupported(SensorDirectChannel.TYPE_MEMORY_FILE)
        val supported = typeOk &&
            accelRate > SensorDirectChannel.RATE_STOP &&
            gyroRate > SensorDirectChannel.RATE_STOP
        return Capability(
            supported = supported,
            accelRateLevel = accelRate,
            gyroRateLevel = gyroRate,
            reason = if (supported) "ok" else "memory-file channel or direct rate unsupported",
        )
    }

    @Synchronized
    override fun start() {
        if (isRunning) return
        val cap = capability()
        if (!cap.supported) {
            Log.w(Streams.IMU, "Direct channel UNAVAILABLE: ${cap.reason}")
            return
        }
        val bytes = SensorDirectReport.RECORD_SIZE * SLOTS
        val mf = MemoryFile("mappilot-imu-direct", bytes)
        val ch = sensorManager.createDirectChannel(mf)

        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)!!
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)!!
        syncEngine.registerStream(StreamIds.IMU_ACCEL, TimestampSource.REALTIME, rateLevelToHz(cap.accelRateLevel))
        syncEngine.registerStream(StreamIds.IMU_GYRO, TimestampSource.REALTIME, rateLevelToHz(cap.gyroRateLevel))
        ch.configure(accel, cap.accelRateLevel)
        ch.configure(gyro, cap.gyroRateLevel)

        memoryFile = mf
        channel = ch
        isRunning = true

        pollLoop = Thread({ pollUntilStopped(mf, bytes) }, "imu-direct-reader").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        Log.i(Streams.IMU, "Direct channel started accelRate=${cap.accelRateLevel} gyroRate=${cap.gyroRateLevel}")
    }

    private fun pollUntilStopped(mf: MemoryFile, bytes: Int) {
        val raw = ByteArray(bytes)
        val slots = SensorDirectReport.recordCount(bytes)
        var lastCounter = 0L
        val fresh = ArrayList<SensorDirectReport.Record>(slots)
        while (isRunning) {
            // Snapshot the whole shared region, then decode against the copy.
            mf.readBytes(raw, 0, 0, bytes)
            val buf = ByteBuffer.wrap(raw).order(ByteOrder.nativeOrder())
            fresh.clear()
            var maxCounter = lastCounter
            for (i in 0 until slots) {
                val rec = SensorDirectReport.decode(buf, i * SensorDirectReport.RECORD_SIZE) ?: continue
                if (rec.atomicCounter > lastCounter) {
                    fresh.add(rec)
                    if (rec.atomicCounter > maxCounter) maxCounter = rec.atomicCounter
                }
            }
            if (fresh.isNotEmpty()) {
                fresh.sortBy { it.atomicCounter }
                for (rec in fresh) emit(rec)
                lastCounter = maxCounter
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
    }

    private fun emit(rec: SensorDirectReport.Record) {
        val (channelEnum, streamId) = when (rec.sensorType) {
            Sensor.TYPE_ACCELEROMETER -> ImuChannel.ACCEL to StreamIds.IMU_ACCEL
            Sensor.TYPE_GYROSCOPE -> ImuChannel.GYRO to StreamIds.IMU_GYRO
            else -> return
        }
        val ts = syncEngine.recordSample(streamId, rec.timestampNs)
        val sample = ImuSample(channelEnum, ts, rec.x, rec.y, rec.z, accuracy = -1)
        eventBus.emit(MapPilotEvent.ImuBatch(ts, listOf(sample)))
    }

    @Synchronized
    override fun stop() {
        if (!isRunning) return
        isRunning = false
        pollLoop?.interrupt()
        pollLoop = null
        try {
            channel?.close()
        } catch (e: Exception) {
            Log.e(Streams.IMU, e, "closing direct channel")
        }
        memoryFile?.close()
        channel = null
        memoryFile = null
        Log.i(Streams.IMU, "Direct channel stopped")
    }

    private fun rateLevelToHz(level: Int): Double = when (level) {
        SensorDirectChannel.RATE_NORMAL -> 50.0
        SensorDirectChannel.RATE_FAST -> 200.0
        SensorDirectChannel.RATE_VERY_FAST -> 800.0
        else -> 0.0
    }

    private companion object {
        const val SLOTS = 256
        const val POLL_INTERVAL_MS = 2L
    }
}
