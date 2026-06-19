package com.mappilot.core.model

/** ARCore-aligned tracking state. */
enum class TrackingState { TRACKING, PAUSED, STOPPED }

/** Why tracking is paused/limited, surfaced rather than hidden. */
enum class TrackingFailureReason {
    NONE,
    BAD_STATE,
    INSUFFICIENT_LIGHT,
    EXCESSIVE_MOTION,
    INSUFFICIENT_FEATURES,
    CAMERA_UNAVAILABLE,
}

/** A 6-DoF pose in the VIO (SLAM-local) frame. */
data class Pose(
    val timestampNs: Long,
    val position: Vector3,
    val orientation: Quaternion,
    val trackingState: TrackingState,
    val failureReason: TrackingFailureReason,
    val confidence: Float,
)

/**
 * A pose expressed in the local ENU frame after Umeyama alignment.
 * [simTransformId] identifies which similarity solution produced it, so a
 * later re-alignment can be told apart from the original.
 */
data class EnuPose(
    val timestampNs: Long,
    val enu: EnuPoint,
    val orientation: Quaternion,
    val simTransformId: Long,
)

/** Sparse feature point in the VIO frame, optionally georeferenced. */
data class Landmark(
    val id: Long,
    val position: Vector3,
    val geo: GeoPoint?,
    val confidence: Float,
)

/** A selected keyframe: pose-graph anchor plus its intrinsics. */
data class Keyframe(
    val frameId: Long,
    val timestampNs: Long,
    val pose: Pose,
    val enuPose: EnuPose?,
    val intrinsics: CameraIntrinsics?,
)
