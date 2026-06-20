package com.mappilot.slam.core

import com.mappilot.core.model.TrackingFailureReason
import com.mappilot.core.model.TrackingState
import kotlinx.coroutines.flow.StateFlow

/** Tuning for SLAM session + keyframe selection. */
data class SlamConfig(
    /** Min translation (m) since the last keyframe to consider a new one. */
    val keyframeMinTranslationM: Double = 0.3,
    /** Min rotation (deg) since the last keyframe to consider a new one. */
    val keyframeMinRotationDeg: Double = 8.0,
    /** Min elapsed time (ns) between keyframes regardless of motion. */
    val keyframeMinIntervalNs: Long = 250_000_000, // 4 Hz cap
)

/** Live SLAM status for the HUD and analytics. All values from the tracker. */
data class SlamState(
    val available: Boolean = false,
    val trackingState: TrackingState = TrackingState.STOPPED,
    val failureReason: TrackingFailureReason = TrackingFailureReason.NONE,
    val poseCount: Long = 0,
    val keyframeCount: Int = 0,
    val landmarkCount: Int = 0,
    val trajectoryLengthM: Double = 0.0,
    /** Quality 0..1 derived from tracking state + feature support; -1 if unknown. */
    val quality: Float = -1f,
    /** Real camera intrinsics from the tracker (ARCore), when the device's Camera2 doesn't report them. */
    val cameraIntrinsics: com.mappilot.core.model.CameraIntrinsics? = null,
    val depthAvailable: Boolean = false,
    val unavailableReason: String? = null,
)

/**
 * Backend-agnostic SLAM session. The ARCore backend ([com.mappilot.slam.arcore])
 * is the default; the interface lets the trajectory/fusion/UI layers be tested
 * against a deterministic backend without ARCore hardware.
 *
 * The engine emits PoseUpdate / KeyframeSelected / LandmarksUpdated to the event
 * bus on its own thread; consumers subscribe rather than poll.
 */
interface SlamEngine {
    val state: StateFlow<SlamState>

    /** Begin a session. If the backend is unavailable, [state] reflects it loudly. */
    fun start(config: SlamConfig = SlamConfig())

    fun stop()

    val isRunning: Boolean

    /**
     * Metric depth (metres) at normalized image coords [uNorm],[vNorm] in [0,1],
     * from the tracker's depth map (ARCore Depth API). Returns NaN when depth is
     * unavailable — never a fabricated distance. Default: unavailable.
     */
    fun depthAt(uNorm: Float, vNorm: Float): Float = Float.NaN
}
