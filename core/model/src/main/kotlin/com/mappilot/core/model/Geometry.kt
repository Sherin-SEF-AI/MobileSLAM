package com.mappilot.core.model

/**
 * 3D vector in a right-handed coordinate frame. Units are context-dependent
 * (metres for positions, m/s² for accel, rad/s for gyro).
 */
data class Vector3(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    companion object {
        val ZERO = Vector3(0.0, 0.0, 0.0)
    }
}

/** Hamilton quaternion (x, y, z, w) representing an orientation. */
data class Quaternion(
    val x: Double,
    val y: Double,
    val z: Double,
    val w: Double,
) {
    companion object {
        val IDENTITY = Quaternion(0.0, 0.0, 0.0, 1.0)
    }
}

/**
 * Pinhole + radial-tangential distortion intrinsics, OpenCV convention.
 * Sourced from ARCore [CameraIntrinsics] or a per-device calibration file —
 * never assumed when absent.
 */
data class CameraIntrinsics(
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
    val k1: Double = 0.0,
    val k2: Double = 0.0,
    val p1: Double = 0.0,
    val p2: Double = 0.0,
    val k3: Double = 0.0,
    val imageWidth: Int,
    val imageHeight: Int,
)

/** WGS84 geographic point. */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
)

/** Local East-North-Up position in metres relative to a session origin. */
data class EnuPoint(
    val east: Double,
    val north: Double,
    val up: Double,
)
