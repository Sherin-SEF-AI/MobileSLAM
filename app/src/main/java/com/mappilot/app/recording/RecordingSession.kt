package com.mappilot.app.recording

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.mappilot.app.BuildConfig
import com.mappilot.app.capture.SensorHub
import com.mappilot.core.common.bus.EventBus
import com.mappilot.core.common.config.ConfigProvider
import com.mappilot.core.common.log.Log
import com.mappilot.core.common.log.Streams
import com.mappilot.core.common.time.TimeSource
import com.mappilot.core.model.MapPilotEvent
import com.mappilot.core.model.StreamHealth
import com.mappilot.core.timesync.SyncEngine
import com.mappilot.recording.mcap.McapTripWriter
import com.mappilot.recording.video.FrameTimestampMap
import com.mappilot.recording.video.Mp4Encoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Owns one capture session's persistence. All MCAP writes happen on a single
 * dedicated writer thread: high-rate IMU is drained from lock-free ring buffers,
 * while camera/GNSS/pose/asset/event records arrive via the bus and are posted to
 * the same thread. Chunks are sealed (flushed) on an interval for crash survival
 * and the file rolls to a new segment past a size threshold.
 *
 * Recording is never gated by perception or upload (§10) — this writes whatever
 * the sensors produce, independently of any downstream consumer.
 */
class RecordingSession(
    private val tripDir: File,
    private val tripId: Long,
    private val sensorHub: SensorHub,
    private val eventBus: EventBus,
    private val timeSource: TimeSource,
    private val syncEngine: SyncEngine,
    private val configProvider: ConfigProvider,
) {
    private val writerThread = HandlerThread("mcap-writer", Thread.NORM_PRIORITY + 1).also { it.start() }
    private val writer = Handler(writerThread.looper)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val frameMap = FrameTimestampMap()
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    private var mcap: McapTripWriter? = null
    private var encoder: Mp4Encoder? = null
    private var segmentIndex = 0
    private val segmentFiles = ArrayList<String>()
    private val startedNs = timeSource.elapsedRealtimeNanos()
    @Volatile private var stopped = false

    val mp4File = File(tripDir, "trip.mp4")

    fun start() {
        tripDir.mkdirs()
        openSegment()

        // Ensure sources are producing; camera must be open before we attach the
        // encoder surface.
        if (!sensorHub.camera.isRunning) sensorHub.camera.start()
        if (!sensorHub.imu.isRunning) sensorHub.imu.start()
        if (!sensorHub.gnss.isRunning) sensorHub.gnss.start()
        // Fill the lossless IMU rings now that the writer thread will drain them.
        sensorHub.imu.setBuffering(true)

        startVideo()
        subscribeBus()
        scheduleImuDrain()
        scheduleSeal()
        Log.i(Streams.RECORDING, "Recording session $tripId started -> $tripDir")
    }

    private fun openSegment() {
        val file = File(tripDir, segmentName(segmentIndex))
        segmentFiles.add(file.name)
        val w = McapTripWriter(file.outputStream().buffered(), library = "mappilot/${BuildConfig.VERSION_NAME}")
        w.start()
        writeCalibration(w)
        mcap = w
    }

    private fun writeCalibration(w: McapTripWriter) {
        val cam = sensorHub.camera
        w.writeCalibration(
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            osVersion = "Android ${Build.VERSION.RELEASE}",
            intrinsics = null,
            cameraTimestampSource = cam.timestampSource.name,
            timebaseOffsetsNs = syncEngine.health.value.streams.mapValues { it.value.appliedOffsetNs },
            ts = timeSource.elapsedRealtimeNanos(),
        )
    }

    private fun startVideo() {
        val cam = sensorHub.camera
        val cfg = configProvider.current()
        val w = if (cam.resolution.width > 0) cam.resolution.width else cfg.videoWidth
        val h = if (cam.resolution.height > 0) cam.resolution.height else cfg.videoHeight
        val enc = Mp4Encoder(w, h, cfg.targetFps)
        try {
            enc.start(mp4File.absolutePath)
            sensorHub.camera.setRecordingSurface(enc.inputSurface)
            encoder = enc
        } catch (e: Exception) {
            // Recording must not be blocked by video; MCAP continues without mp4.
            Log.e(Streams.RECORDING, e, "Video encoder unavailable; continuing MCAP-only")
            encoder = null
        }
    }

    private fun subscribeBus() {
        eventBus.events
            .onEach { event ->
                when (event) {
                    is MapPilotEvent.FrameCaptured -> writer.post {
                        val ts = event.frame.timestampNs
                        // Same sensor timeline as video; record the explicit map entry.
                        frameMap.add(event.frame.frameId, ts, ts)
                        mcap?.writeCameraFrame(event.frame.copy(videoPtsNs = ts))
                    }
                    is MapPilotEvent.GnssFixReceived -> writer.post { mcap?.writeGnss(event.epoch) }
                    is MapPilotEvent.PoseUpdate -> writer.post { mcap?.writePose(event.pose) }
                    is MapPilotEvent.EnuPoseUpdate -> writer.post { mcap?.writeEnuPose(event.pose) }
                    is MapPilotEvent.LandmarksUpdated -> writer.post { mcap?.writeLandmarks(event.timestampNs, event.landmarks) }
                    is MapPilotEvent.AssetsExtracted -> writer.post { mcap?.writeAssets(event.timestampNs, event.assets) }
                    is MapPilotEvent.DeviceEventRaised -> writer.post { mcap?.writeEvent(event.event) }
                    is MapPilotEvent.RotationUpdate -> writer.post { mcap?.writeRotation(event.sample) }
                    else -> Unit // ImuBatch handled via ring-buffer drain, not the lossy bus
                }
            }
            .launchIn(scope)
    }

    private fun scheduleImuDrain() {
        writer.post(object : Runnable {
            override fun run() {
                if (stopped) return
                drainImu()
                writer.postDelayed(this, IMU_DRAIN_INTERVAL_MS)
            }
        })
    }

    private fun drainImu() {
        val w = mcap ?: return
        sensorHub.imu.sampleBuffer.drain { w.writeImu(it) }
        sensorHub.imu.rotationBuffer.drain { w.writeRotation(it) }
    }

    private fun scheduleSeal() {
        val interval = configProvider.current().mcapChunkSealIntervalMs
        writer.postDelayed(object : Runnable {
            override fun run() {
                if (stopped) return
                mcap?.seal()
                maybeRollover()
                writer.postDelayed(this, interval)
            }
        }, interval)
    }

    private fun maybeRollover() {
        val w = mcap ?: return
        if (w.bytesWritten >= configProvider.current().mcapSegmentRolloverBytes) {
            Log.i(Streams.RECORDING, "Rolling over segment $segmentIndex (${w.bytesWritten} bytes)")
            w.finish()
            segmentIndex++
            openSegment()
        }
    }

    /** Stops capture-to-disk and finalizes all artifacts. Returns the result. */
    fun stop(): RecordingResult {
        stopped = true
        sensorHub.imu.setBuffering(false)
        scope.cancel()
        sensorHub.camera.setRecordingSurface(null)
        encoder?.stop()

        // Final drain + finalize on the writer thread, then tear it down.
        val done = java.util.concurrent.CountDownLatch(1)
        writer.post {
            drainImu()
            mcap?.finish()
            done.countDown()
        }
        done.await(STOP_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        writerThread.quitSafely()

        // Frame map CSV + metadata.
        File(tripDir, "frame_timestamps.csv").bufferedWriter().use { frameMap.writeCsv(it) }
        val endedNs = timeSource.elapsedRealtimeNanos()
        writeMetadata(endedNs)

        Log.i(Streams.RECORDING, "Recording session $tripId stopped: ${frameMap.size} frames, ${segmentFiles.size} segments")
        return RecordingResult(
            tripId = tripId,
            tripDir = tripDir,
            segments = segmentFiles.toList(),
            mp4Path = if (encoder != null) mp4File.absolutePath else null,
            frameCount = frameMap.size,
            startedNs = startedNs,
            endedNs = endedNs,
        )
    }

    private fun writeMetadata(endedNs: Long) {
        val cam = sensorHub.camera
        val health = syncEngine.health.value
        val metadata = TripMetadata(
            tripId = tripId,
            startedNs = startedNs,
            endedNs = endedNs,
            device = DeviceInfo(Build.MANUFACTURER, Build.MODEL, "Android ${Build.VERSION.RELEASE}", BuildConfig.VERSION_NAME),
            calibration = CalibrationInfo(
                hasCameraIntrinsics = false,
                cameraTimestampSource = cam.timestampSource.name,
            ),
            syncHealth = health.streams.values.map { it.toInfo() },
            segments = segmentFiles.toList(),
            mp4Path = if (encoder != null) mp4File.name else null,
            frameCount = frameMap.size,
        )
        File(tripDir, "trip_metadata.json").writeText(json.encodeToString(metadata))
    }

    private fun StreamHealth.toInfo() = StreamHealthInfo(
        streamId = streamId,
        source = source.name,
        appliedOffsetNs = appliedOffsetNs,
        rateHz = rateHz,
        samplesReceived = samplesReceived,
        samplesDropped = samplesDropped,
        outOfOrderCount = outOfOrderCount,
        gapCount = gapCount,
    )

    private fun segmentName(index: Int): String =
        if (index == 0) "trip.mcap" else "trip.%04d.mcap".format(index)

    private companion object {
        const val IMU_DRAIN_INTERVAL_MS = 20L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

data class RecordingResult(
    val tripId: Long,
    val tripDir: File,
    val segments: List<String>,
    val mp4Path: String?,
    val frameCount: Int,
    val startedNs: Long,
    val endedNs: Long,
)
