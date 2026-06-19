package com.mappilot.slam.core

import com.mappilot.core.model.Pose
import com.mappilot.core.model.Quaternion
import com.mappilot.core.model.TrackingState
import com.mappilot.core.model.Vector3
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Decides which tracked poses become keyframes, by motion (translation/rotation)
 * since the last keyframe with a minimum time gap. Pure and deterministic so the
 * policy is unit-tested independently of any tracker. Only TRACKING poses are
 * eligible — a paused tracker never produces keyframes.
 */
class KeyframeSelector(private val config: SlamConfig) {

    private var lastKeyframe: Pose? = null

    val keyframeCount: Int get() = count
    private var count = 0

    fun reset() {
        lastKeyframe = null
        count = 0
    }

    /** Returns true and records this pose as a keyframe if it qualifies. */
    fun offer(pose: Pose): Boolean {
        if (pose.trackingState != TrackingState.TRACKING) return false
        val last = lastKeyframe
        if (last == null) {
            lastKeyframe = pose; count++
            return true
        }
        if (pose.timestampNs - last.timestampNs < config.keyframeMinIntervalNs) return false

        val translation = distance(pose.position, last.position)
        val rotation = Math.toDegrees(angleBetween(pose.orientation, last.orientation))
        return if (translation >= config.keyframeMinTranslationM || rotation >= config.keyframeMinRotationDeg) {
            lastKeyframe = pose; count++
            true
        } else {
            false
        }
    }

    private fun distance(a: Vector3, b: Vector3): Double {
        val dx = a.x - b.x; val dy = a.y - b.y; val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** Smallest angle (radians) between two orientations. */
    private fun angleBetween(a: Quaternion, b: Quaternion): Double {
        val dot = abs(a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w).coerceIn(0.0, 1.0)
        return 2.0 * acos(dot)
    }
}
