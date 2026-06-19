package com.mappilot.assets.extraction

import com.mappilot.core.model.CameraIntrinsics
import com.mappilot.core.model.Quaternion
import com.mappilot.core.model.Vector3

/**
 * Backprojects a pixel + metric depth into a world-frame 3D point using the
 * pinhole model and the camera pose. Pure and unit-tested.
 *
 * Convention: a camera-space point is `((u-cx)/fx·d, (v-cy)/fy·d, d)` with +z
 * forward (OpenCV pinhole). The camera pose `(position, orientation)` is
 * camera→world, so `P_world = R(orientation)·P_cam + position`. The on-device
 * adapter maps ARCore's camera frame into this convention before calling in;
 * the geometry here is convention-explicit so it is fully testable.
 */
object Backprojection {

    fun cameraPoint(u: Double, v: Double, depthM: Double, intr: CameraIntrinsics): Vector3 =
        Vector3(
            x = (u - intr.cx) / intr.fx * depthM,
            y = (v - intr.cy) / intr.fy * depthM,
            z = depthM,
        )

    fun cameraToWorld(camPoint: Vector3, position: Vector3, orientation: Quaternion): Vector3 {
        val r = rotate(orientation, camPoint)
        return Vector3(r.x + position.x, r.y + position.y, r.z + position.z)
    }

    /**
     * Full backprojection: image pixel ([u],[v]) at metric [depthM] → world point.
     * Returns null when depth is non-positive/NaN — no fabricated placement.
     */
    fun backproject(
        u: Double,
        v: Double,
        depthM: Double,
        intr: CameraIntrinsics,
        position: Vector3,
        orientation: Quaternion,
    ): Vector3? {
        if (!depthM.isFinite() || depthM <= 0.0) return null
        return cameraToWorld(cameraPoint(u, v, depthM, intr), position, orientation)
    }

    /** Rotate a vector by a unit quaternion: v' = q·v·q⁻¹. */
    fun rotate(q: Quaternion, v: Vector3): Vector3 {
        // t = 2 * cross(q.xyz, v); v' = v + q.w*t + cross(q.xyz, t)
        val tx = 2.0 * (q.y * v.z - q.z * v.y)
        val ty = 2.0 * (q.z * v.x - q.x * v.z)
        val tz = 2.0 * (q.x * v.y - q.y * v.x)
        val cx = q.y * tz - q.z * ty
        val cy = q.z * tx - q.x * tz
        val cz = q.x * ty - q.y * tx
        return Vector3(
            v.x + q.w * tx + cx,
            v.y + q.w * ty + cy,
            v.z + q.w * tz + cz,
        )
    }
}
