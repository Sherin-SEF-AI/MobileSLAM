package com.mappilot.viz.render3d

import com.mappilot.core.model.Keyframe
import com.mappilot.core.model.Landmark
import com.mappilot.core.model.Quaternion
import com.mappilot.core.model.Vector3

/**
 * Pure geometry prep for the 3D scene: centres the cloud on its centroid and
 * builds keyframe frustum line segments from poses. Pure so the vertex assembly
 * is unit-tested; the GL upload/draw is device-side.
 */
object PointCloudScene {

    /** Interleaved point vertices (x,y,z,confidence), centred on [centroid]. */
    fun pointVertices(landmarks: List<Landmark>, centroid: Vector3): FloatArray {
        val out = FloatArray(landmarks.size * 4)
        var i = 0
        for (l in landmarks) {
            out[i++] = (l.position.x - centroid.x).toFloat()
            out[i++] = (l.position.y - centroid.y).toFloat()
            out[i++] = (l.position.z - centroid.z).toFloat()
            out[i++] = l.confidence
        }
        return out
    }

    fun centroid(landmarks: List<Landmark>): Vector3 {
        if (landmarks.isEmpty()) return Vector3.ZERO
        var x = 0.0; var y = 0.0; var z = 0.0
        for (l in landmarks) { x += l.position.x; y += l.position.y; z += l.position.z }
        val n = landmarks.size.toDouble()
        return Vector3(x / n, y / n, z / n)
    }

    /**
     * Line-segment vertices (pairs of x,y,z) for the keyframe frustums: apex →
     * 4 corners and the far rectangle, scaled by [size] metres, centred on
     * [centroid]. 8 segments per keyframe = 16 vertices = 48 floats.
     */
    fun frustumLines(keyframes: List<Keyframe>, centroid: Vector3, size: Float = 0.25f): FloatArray {
        val s = size.toDouble()
        // Camera-local frustum: apex at origin, looking +z, 4 corners on the far plane.
        val apex = Vector3(0.0, 0.0, 0.0)
        val corners = listOf(
            Vector3(-s, -s, 2 * s), Vector3(s, -s, 2 * s),
            Vector3(s, s, 2 * s), Vector3(-s, s, 2 * s),
        )
        val out = ArrayList<Float>(keyframes.size * 48)
        for (kf in keyframes) {
            val pos = kf.pose.position
            val q = kf.pose.orientation
            fun world(v: Vector3): FloatArray {
                val r = rotate(q, v)
                return floatArrayOf(
                    (r.x + pos.x - centroid.x).toFloat(),
                    (r.y + pos.y - centroid.y).toFloat(),
                    (r.z + pos.z - centroid.z).toFloat(),
                )
            }
            val a = world(apex)
            val cw = corners.map { world(it) }
            // apex → each corner
            for (c in cw) { out.addAll(a.toList()); out.addAll(c.toList()) }
            // far rectangle
            for (i in 0 until 4) {
                out.addAll(cw[i].toList()); out.addAll(cw[(i + 1) % 4].toList())
            }
        }
        return out.toFloatArray()
    }

    /** Rotate a vector by a unit quaternion (v' = q·v·q⁻¹). */
    internal fun rotate(q: Quaternion, v: Vector3): Vector3 {
        val tx = 2.0 * (q.y * v.z - q.z * v.y)
        val ty = 2.0 * (q.z * v.x - q.x * v.z)
        val tz = 2.0 * (q.x * v.y - q.y * v.x)
        return Vector3(
            v.x + q.w * tx + (q.y * tz - q.z * ty),
            v.y + q.w * ty + (q.z * tx - q.x * tz),
            v.z + q.w * tz + (q.x * ty - q.y * tx),
        )
    }
}
