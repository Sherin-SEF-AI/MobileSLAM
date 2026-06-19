package com.mappilot.sensors.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import com.mappilot.core.common.bus.EventBus
import com.mappilot.core.common.capture.CaptureSource
import com.mappilot.core.common.config.ConfigProvider
import com.mappilot.core.common.log.Log
import com.mappilot.core.common.log.Streams
import com.mappilot.core.model.CameraIntrinsics
import com.mappilot.core.model.FrameMeta
import com.mappilot.core.model.MapPilotEvent
import com.mappilot.core.model.StreamIds
import com.mappilot.core.model.TimestampSource
import com.mappilot.core.timesync.SyncEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Camera2-backed frame-metadata capture for Phase 1: opens the back camera,
 * detects its timestamp source, and emits real per-frame metadata (timestamp,
 * exposure, ISO, calibrated intrinsics) extracted from each [TotalCaptureResult].
 *
 * Frame timestamps are normalized to the unified base by the [SyncEngine]; when
 * `SENSOR_INFO_TIMESTAMP_SOURCE` is UNKNOWN, the SyncEngine measures and applies
 * a per-device offset (ADR 0002) instead of trusting the raw value.
 *
 * A preview [Surface] may be supplied; otherwise an internal [SurfaceTexture]
 * drives the repeating request so metadata flows even headless. This is the
 * record-adjacent path; the ARCore SharedCamera SLAM path lands in Phase 3.
 */
@Singleton
class CameraCaptureSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configProvider: ConfigProvider,
    private val syncEngine: SyncEngine,
    private val eventBus: EventBus,
) : CaptureSource {

    override val name: String = "camera"

    private val cameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    @Volatile
    override var isRunning: Boolean = false
        private set

    @Volatile var resolution: Size = Size(0, 0); private set
    @Volatile var timestampSource: TimestampSource = TimestampSource.UNKNOWN; private set

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var internalSurface: Surface? = null

    @Volatile private var previewSurface: Surface? = null
    @Volatile private var recordingSurface: Surface? = null
    @Volatile private var analysisCallback: ((CameraImage) -> Unit)? = null
    @Volatile private var analysisBusy = false
    private var imageReader: ImageReader? = null
    private val analysisFrameId = AtomicLong(0)
    private val analysisSize = Size(640, 480)
    private val frameId = AtomicLong(0)
    private var cameraId: String? = null
    private var characteristics: CameraCharacteristics? = null

    fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /** Provide a UI preview surface. Takes effect on next [start]/reconfigure. */
    fun setPreviewSurface(surface: Surface?) {
        previewSurface = surface
    }

    /**
     * Provide the video-encoder input surface so recorded frames are captured to
     * mp4 alongside metadata. Rebuilds the capture session if already running.
     */
    fun setRecordingSurface(surface: Surface?) {
        recordingSurface = surface
        val cam = device ?: return
        val h = handler ?: return
        h.post { runCatching { reconfigure(cam, h) } }
    }

    private fun reconfigure(camera: CameraDevice, h: Handler) {
        runCatching { session?.close() }
        session = null
        createSession(camera, h)
    }

    /**
     * Provide a perception callback. The camera attaches a YUV ImageReader target;
     * frames are delivered (and dropped while the consumer is busy) so perception
     * never back-pressures the capture/record path. Set null to detach.
     */
    fun setAnalysisCallback(cb: ((CameraImage) -> Unit)?) {
        analysisCallback = cb
        val cam = device ?: return
        val h = handler ?: return
        h.post { runCatching { reconfigure(cam, h) } }
    }

    /** Called by the perception consumer when it has finished a delivered frame. */
    fun onAnalysisComplete() { analysisBusy = false }

    @Synchronized
    override fun start() {
        if (isRunning) return
        if (!hasCameraPermission()) {
            Log.w(Streams.CAMERA, "Camera UNAVAILABLE: CAMERA permission not granted")
            return
        }
        val id = selectBackCamera() ?: run {
            Log.w(Streams.CAMERA, "Camera UNAVAILABLE: no camera found")
            return
        }
        cameraId = id
        val chars = cameraManager.getCameraCharacteristics(id)
        characteristics = chars
        resolution = chooseSize(chars)
        timestampSource = detectTimestampSource(chars)
        syncEngine.registerStream(StreamIds.CAMERA, timestampSource, configProvider.current().targetFps.toDouble())

        val ht = HandlerThread("camera").also { it.start() }
        val h = Handler(ht.looper)
        thread = ht
        handler = h

        try {
            openCamera(id, h)
        } catch (se: SecurityException) {
            Log.e(Streams.CAMERA, se, "Camera open denied")
            ht.quitSafely()
            return
        }
        isRunning = true
        Log.i(Streams.CAMERA, "Camera starting id=$id res=$resolution tsSource=$timestampSource")
    }

    @Suppress("MissingPermission") // guarded by hasCameraPermission()
    private fun openCamera(id: String, h: Handler) {
        cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                createSession(camera, h)
            }

            override fun onDisconnected(camera: CameraDevice) {
                Log.w(Streams.CAMERA, "Camera disconnected")
                camera.close()
                device = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(Streams.CAMERA, null, "Camera error $error")
                camera.close()
                device = null
            }
        }, h)
    }

    private fun createSession(camera: CameraDevice, h: Handler) {
        val primary = previewSurface ?: createInternalSurface()
        val analysisSurface = if (analysisCallback != null) createAnalysisReader(h) else null
        val targets = listOfNotNull(primary, recordingSurface, analysisSurface)
        val executor = Executor { command -> h.post(command) }
        val outputs = targets.map { OutputConfiguration(it) }
        // RECORD template when an encoder surface is attached, else PREVIEW.
        val template = if (recordingSurface != null) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputs,
            executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    val request = camera.createCaptureRequest(template).apply {
                        targets.forEach { addTarget(it) }
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    }.build()
                    s.setRepeatingRequest(request, captureCallback, h)
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    Log.e(Streams.CAMERA, null, "Capture session configuration failed")
                }
            },
        )
        camera.createCaptureSession(config)
    }

    private fun createAnalysisReader(h: Handler): Surface {
        imageReader?.close()
        val reader = ImageReader.newInstance(
            analysisSize.width, analysisSize.height, ImageFormat.YUV_420_888, 2,
        )
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val cb = analysisCallback
                if (cb == null || analysisBusy) return@setOnImageAvailableListener
                analysisBusy = true // consumer clears via onAnalysisComplete()
                val frame = CameraImage(
                    frameId = analysisFrameId.getAndIncrement(),
                    timestampNs = image.timestamp,
                    width = image.width,
                    height = image.height,
                    nv21 = image.toNv21(),
                )
                cb(frame)
            } finally {
                image.close()
            }
        }, h)
        imageReader = reader
        return reader.surface
    }

    private fun createInternalSurface(): Surface {
        val st = SurfaceTexture(0).apply {
            setDefaultBufferSize(resolution.width, resolution.height)
            detachFromGLContext()
        }
        val surface = Surface(st)
        surfaceTexture = st
        internalSurface = surface
        return surface
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            val rawTs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            val ts = syncEngine.recordSample(StreamIds.CAMERA, rawTs)
            val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
            val intrinsics = extractIntrinsics(result)
            val meta = FrameMeta(
                frameId = frameId.getAndIncrement(),
                timestampNs = ts,
                width = resolution.width,
                height = resolution.height,
                exposureNs = exposure,
                iso = iso,
                intrinsics = intrinsics,
                videoPtsNs = 0L, // video sidecar lands in Phase 2
                isKeyframe = false,
            )
            eventBus.emit(MapPilotEvent.FrameCaptured(ts, meta))
        }
    }

    private fun extractIntrinsics(result: TotalCaptureResult): CameraIntrinsics? {
        val chars = characteristics ?: return null
        val calibration = result.get(CaptureResult.LENS_INTRINSIC_CALIBRATION)
            ?: chars.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
        val distortion = result.get(CaptureResult.LENS_DISTORTION)
            ?: chars.get(CameraCharacteristics.LENS_DISTORTION)
        val array = chars.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
            ?: return null
        return CameraIntrinsicsMapper.map(
            calibration = calibration,
            distortion = distortion,
            arrayWidth = array.width(),
            arrayHeight = array.height(),
            outputWidth = resolution.width,
            outputHeight = resolution.height,
        )
    }

    @Synchronized
    override fun stop() {
        if (!isRunning) return
        isRunning = false
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
        internalSurface?.release()
        surfaceTexture?.release()
        imageReader?.close()
        internalSurface = null
        surfaceTexture = null
        imageReader = null
        analysisBusy = false
        thread?.quitSafely()
        thread = null
        handler = null
        Log.i(Streams.CAMERA, "Camera stopped")
    }

    private fun selectBackCamera(): String? {
        val ids = cameraManager.cameraIdList
        ids.firstOrNull {
            cameraManager.getCameraCharacteristics(it)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }?.let { return it }
        return ids.firstOrNull()
    }

    private fun detectTimestampSource(chars: CameraCharacteristics): TimestampSource =
        when (chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)) {
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> TimestampSource.REALTIME
            else -> TimestampSource.UNKNOWN
        }

    private fun chooseSize(chars: CameraCharacteristics): Size {
        val config = configProvider.current()
        val target = Size(config.videoWidth, config.videoHeight)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return target
        val sizes = map.getOutputSizes(ImageFormat.PRIVATE)
            ?: map.getOutputSizes(SurfaceTexture::class.java)
            ?: return target
        val targetArea = target.width.toLong() * target.height
        // Prefer the supported size whose area is closest to the configured target.
        return sizes.minByOrNull { kotlin.math.abs(it.width.toLong() * it.height - targetArea) }
            ?: target
    }
}
