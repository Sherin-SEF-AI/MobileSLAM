package com.mappilot.slam.arcore

import android.content.Context
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState as ArTrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.mappilot.core.common.bus.EventBus
import com.mappilot.core.common.log.Log
import com.mappilot.core.common.log.Streams
import com.mappilot.core.model.CameraIntrinsics
import com.mappilot.core.model.Keyframe
import com.mappilot.core.model.Landmark
import com.mappilot.core.model.MapPilotEvent
import com.mappilot.core.model.Pose
import com.mappilot.core.model.Quaternion
import com.mappilot.core.model.TrackingFailureReason
import com.mappilot.core.model.TrackingState
import com.mappilot.core.model.Vector3
import com.mappilot.slam.core.KeyframeSelector
import com.mappilot.slam.core.PoseGraph
import com.mappilot.slam.core.SlamConfig
import com.mappilot.slam.core.SlamEngine
import com.mappilot.slam.core.SlamState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ARCore visual-inertial SLAM backend. Drives an ARCore [Session] on a dedicated
 * thread with an offscreen GL context, emitting real VIO poses, sparse feature
 * landmarks (`acquirePointCloud`), and keyframes to the event bus.
 *
 * Availability is checked up front: on a device without ARCore support the engine
 * reports `available = false` with a reason and never fabricates poses.
 *
 * NOTE (device integration): this runs ARCore with its own camera. Unifying it
 * with the recording camera via `SharedCamera` is the on-hardware integration
 * step tracked in ADR 0009; the pose/landmark/keyframe outputs and their bus
 * contract are final here.
 */
@Singleton
class ArcoreSlamEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eventBus: EventBus,
) : SlamEngine {

    private val _state = MutableStateFlow(SlamState())
    override val state: StateFlow<SlamState> = _state.asStateFlow()

    @Volatile override var isRunning: Boolean = false
        private set

    private var thread: Thread? = null
    private var session: Session? = null
    private var landmarkEmitCounter = 0
    private var depthSupported = false

    // Latest depth map (ARCore Depth API), copied off the GL thread so perception
    // can sample it. Masked to 13-bit millimetres on read.
    @Volatile private var depthData: ShortArray? = null
    @Volatile private var depthW = 0
    @Volatile private var depthH = 0

    override fun depthAt(uNorm: Float, vNorm: Float): Float {
        val d = depthData ?: return Float.NaN
        if (depthW <= 0 || depthH <= 0) return Float.NaN
        val x = (uNorm * depthW).toInt().coerceIn(0, depthW - 1)
        val y = (vNorm * depthH).toInt().coerceIn(0, depthH - 1)
        val mm = d[y * depthW + x].toInt() and 0x1FFF
        return if (mm > 0) mm / 1000f else Float.NaN
    }

    override fun start(config: SlamConfig) {
        if (isRunning) return
        when (val availability = ArCoreApk.getInstance().checkAvailability(context)) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED -> Unit
            else -> {
                _state.value = SlamState(available = false, unavailableReason = "ARCore: $availability")
                Log.w(Streams.SLAM, "ARCore unavailable: $availability")
                return
            }
        }
        isRunning = true
        thread = Thread({ runSession(config) }, "arcore-slam").apply { start() }
    }

    private fun runSession(config: SlamConfig) {
        val egl = OffscreenEglContext()
        val poseGraph = PoseGraph()
        val keyframeSelector = KeyframeSelector(config)
        try {
            egl.create()
            val s = Session(context).also { session = it }
            depthSupported = s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
            val arConfig = Config(s).apply {
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                focusMode = Config.FocusMode.AUTO
                if (depthSupported) depthMode = Config.DepthMode.AUTOMATIC
                planeFindingMode = Config.PlaneFindingMode.DISABLED // mapping, not AR placement
            }
            s.configure(arConfig)
            s.setCameraTextureName(egl.cameraTextureId)
            s.resume()
            _state.value = SlamState(available = true, trackingState = TrackingState.PAUSED)
            Log.i(Streams.SLAM, "ARCore session started")

            while (isRunning) {
                val frame = try {
                    s.update()
                } catch (e: CameraNotAvailableException) {
                    Log.e(Streams.SLAM, e, "Camera not available")
                    break
                }
                process(frame, poseGraph, keyframeSelector)
            }
        } catch (e: Exception) {
            Log.e(Streams.SLAM, e, "ARCore session error")
            _state.value = _state.value.copy(available = false, unavailableReason = e.message)
        } finally {
            runCatching { session?.pause() }
            runCatching { session?.close() }
            session = null
            egl.release()
            Log.i(Streams.SLAM, "ARCore session closed")
        }
    }

    private fun process(frame: Frame, graph: PoseGraph, selector: KeyframeSelector) {
        val camera = frame.camera
        val ts = frame.timestamp
        val trackingState = camera.trackingState.toModel()
        val pose = Pose(
            timestampNs = ts,
            position = camera.pose.let { Vector3(it.tx().toDouble(), it.ty().toDouble(), it.tz().toDouble()) },
            orientation = camera.pose.let { Quaternion(it.qx().toDouble(), it.qy().toDouble(), it.qz().toDouble(), it.qw().toDouble()) },
            trackingState = trackingState,
            failureReason = camera.trackingFailureReason.toModel(),
            confidence = if (trackingState == TrackingState.TRACKING) 1f else 0f,
        )
        graph.addPose(pose)
        eventBus.emit(MapPilotEvent.PoseUpdate(ts, pose))

        if (selector.offer(pose)) {
            val kf = Keyframe(frameId = graph.poseCount, timestampNs = ts, pose = pose, enuPose = null, intrinsics = null)
            graph.addKeyframe(kf)
            eventBus.emit(MapPilotEvent.KeyframeSelected(ts, kf))
        }

        // Real intrinsics from ARCore (this device's Camera2 reports none).
        val intrinsics = extractIntrinsics(camera)

        var landmarkCount = _state.value.landmarkCount
        if (trackingState == TrackingState.TRACKING && (++landmarkEmitCounter % LANDMARK_EMIT_EVERY == 0)) {
            landmarkCount = emitPointCloud(frame, ts)
            if (depthSupported) acquireDepth(frame)
        }

        _state.value = SlamState(
            available = true,
            trackingState = trackingState,
            failureReason = pose.failureReason,
            poseCount = graph.poseCount,
            keyframeCount = graph.keyframeCount,
            landmarkCount = landmarkCount,
            trajectoryLengthM = graph.trajectoryLengthM,
            quality = qualityOf(trackingState, pose.failureReason),
            cameraIntrinsics = intrinsics,
            depthAvailable = depthData != null,
        )
    }

    private fun extractIntrinsics(camera: com.google.ar.core.Camera): CameraIntrinsics? = try {
        val ci = camera.imageIntrinsics
        val fl = ci.focalLength      // [fx, fy]
        val pp = ci.principalPoint   // [cx, cy]
        val dim = ci.imageDimensions // [w, h]
        CameraIntrinsics(
            fx = fl[0].toDouble(), fy = fl[1].toDouble(),
            cx = pp[0].toDouble(), cy = pp[1].toDouble(),
            imageWidth = dim[0], imageHeight = dim[1],
        )
    } catch (e: Exception) {
        null
    }

    private fun acquireDepth(frame: Frame) {
        try {
            frame.acquireDepthImage16Bits().use { img ->
                val w = img.width; val h = img.height
                val plane = img.planes[0]
                val buf = plane.buffer
                val rowStride = plane.rowStride
                val out = ShortArray(w * h)
                for (y in 0 until h) {
                    var rowOff = y * rowStride
                    for (x in 0 until w) {
                        val lo = buf.get(rowOff).toInt() and 0xFF
                        val hi = buf.get(rowOff + 1).toInt() and 0xFF
                        out[y * w + x] = ((hi shl 8) or lo).toShort()
                        rowOff += 2
                    }
                }
                depthData = out; depthW = w; depthH = h
            }
        } catch (e: Exception) {
            // Depth not ready this frame — leave the previous map; never fabricate.
        }
    }

    private fun emitPointCloud(frame: Frame, ts: Long): Int {
        return try {
            frame.acquirePointCloud().use { cloud ->
                val buf = cloud.points // x,y,z,confidence per point
                val ids = cloud.ids
                val n = buf.remaining() / 4
                val landmarks = ArrayList<Landmark>(n)
                for (i in 0 until n) {
                    val base = i * 4
                    val px = buf.get(base); val py = buf.get(base + 1); val pz = buf.get(base + 2)
                    // Skip non-finite points (ARCore can emit NaN during early tracking);
                    // they're invalid measurements and would corrupt MCAP + DB persistence.
                    if (!px.isFinite() || !py.isFinite() || !pz.isFinite()) continue
                    landmarks.add(
                        Landmark(
                            id = if (ids != null && i < ids.remaining()) ids.get(i).toLong() else i.toLong(),
                            position = Vector3(px.toDouble(), py.toDouble(), pz.toDouble()),
                            geo = null,
                            confidence = buf.get(base + 3),
                        ),
                    )
                }
                if (landmarks.isNotEmpty()) {
                    eventBus.emit(MapPilotEvent.LandmarksUpdated(ts, landmarks))
                }
                landmarks.size
            }
        } catch (e: Exception) {
            Log.w(Streams.SLAM, "point cloud unavailable: ${e.message}")
            _state.value.landmarkCount
        }
    }

    override fun stop() {
        if (!isRunning) return
        isRunning = false
        thread?.join(STOP_TIMEOUT_MS)
        thread = null
        _state.value = _state.value.copy(trackingState = TrackingState.STOPPED)
    }

    private fun qualityOf(state: TrackingState, reason: TrackingFailureReason): Float = when {
        state == TrackingState.TRACKING -> 1f
        reason == TrackingFailureReason.INSUFFICIENT_FEATURES || reason == TrackingFailureReason.INSUFFICIENT_LIGHT -> 0.3f
        reason == TrackingFailureReason.EXCESSIVE_MOTION -> 0.2f
        else -> 0f
    }

    private fun ArTrackingState.toModel(): TrackingState = when (this) {
        ArTrackingState.TRACKING -> TrackingState.TRACKING
        ArTrackingState.PAUSED -> TrackingState.PAUSED
        ArTrackingState.STOPPED -> TrackingState.STOPPED
    }

    private fun com.google.ar.core.TrackingFailureReason.toModel(): TrackingFailureReason = when (this) {
        com.google.ar.core.TrackingFailureReason.NONE -> TrackingFailureReason.NONE
        com.google.ar.core.TrackingFailureReason.BAD_STATE -> TrackingFailureReason.BAD_STATE
        com.google.ar.core.TrackingFailureReason.INSUFFICIENT_LIGHT -> TrackingFailureReason.INSUFFICIENT_LIGHT
        com.google.ar.core.TrackingFailureReason.EXCESSIVE_MOTION -> TrackingFailureReason.EXCESSIVE_MOTION
        com.google.ar.core.TrackingFailureReason.INSUFFICIENT_FEATURES -> TrackingFailureReason.INSUFFICIENT_FEATURES
        com.google.ar.core.TrackingFailureReason.CAMERA_UNAVAILABLE -> TrackingFailureReason.CAMERA_UNAVAILABLE
        else -> TrackingFailureReason.BAD_STATE
    }

    private companion object {
        const val LANDMARK_EMIT_EVERY = 10 // ~3 Hz at 30 fps
        const val STOP_TIMEOUT_MS = 3_000L
    }
}
