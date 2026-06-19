package com.mappilot.slam.core

import com.mappilot.core.model.Keyframe
import com.mappilot.core.model.Pose
import com.mappilot.core.model.TrackingState
import com.mappilot.core.model.Vector3
import kotlin.math.sqrt

/**
 * Accumulates the camera pose graph: the running trajectory length over TRACKING
 * poses (gaps across tracking loss are not counted as travel) and the selected
 * keyframes. Thread-confined to the SLAM thread.
 */
class PoseGraph {
    private val keyframes = ArrayList<Keyframe>()
    private var lastTrackedPosition: Vector3? = null

    var poseCount: Long = 0; private set
    var trajectoryLengthM: Double = 0.0; private set

    fun addPose(pose: Pose) {
        poseCount++
        if (pose.trackingState != TrackingState.TRACKING) {
            // Tracking lost: break the polyline so the gap isn't counted as motion.
            lastTrackedPosition = null
            return
        }
        lastTrackedPosition?.let { trajectoryLengthM += distance(it, pose.position) }
        lastTrackedPosition = pose.position
    }

    fun addKeyframe(keyframe: Keyframe) {
        keyframes.add(keyframe)
    }

    val keyframeCount: Int get() = keyframes.size

    fun keyframes(): List<Keyframe> = keyframes.toList()

    private fun distance(a: Vector3, b: Vector3): Double {
        val dx = a.x - b.x; val dy = a.y - b.y; val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
