package com.mappilot.slam.core

import com.google.common.truth.Truth.assertThat
import com.mappilot.core.model.Pose
import com.mappilot.core.model.Quaternion
import com.mappilot.core.model.TrackingFailureReason
import com.mappilot.core.model.TrackingState
import com.mappilot.core.model.Vector3
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Test

class SlamCoreTest {

    private fun pose(
        ts: Long,
        pos: Vector3 = Vector3.ZERO,
        q: Quaternion = Quaternion.IDENTITY,
        state: TrackingState = TrackingState.TRACKING,
    ) = Pose(ts, pos, q, state, TrackingFailureReason.NONE, 1f)

    private fun yaw(deg: Double): Quaternion {
        val h = Math.toRadians(deg) / 2
        return Quaternion(0.0, 0.0, sin(h), cos(h))
    }

    @Test
    fun `first tracking pose is always a keyframe`() {
        val sel = KeyframeSelector(SlamConfig())
        assertThat(sel.offer(pose(0))).isTrue()
        assertThat(sel.keyframeCount).isEqualTo(1)
    }

    @Test
    fun `keyframe selected after enough translation`() {
        val sel = KeyframeSelector(SlamConfig(keyframeMinTranslationM = 0.3, keyframeMinIntervalNs = 0))
        sel.offer(pose(0))
        assertThat(sel.offer(pose(1, Vector3(0.1, 0.0, 0.0)))).isFalse() // 10 cm < 30 cm
        assertThat(sel.offer(pose(2, Vector3(0.4, 0.0, 0.0)))).isTrue()  // 40 cm
    }

    @Test
    fun `keyframe selected after enough rotation`() {
        val sel = KeyframeSelector(SlamConfig(keyframeMinRotationDeg = 8.0, keyframeMinIntervalNs = 0))
        sel.offer(pose(0))
        assertThat(sel.offer(pose(1, q = yaw(3.0)))).isFalse()
        assertThat(sel.offer(pose(2, q = yaw(15.0)))).isTrue()
    }

    @Test
    fun `min interval throttles keyframes`() {
        val sel = KeyframeSelector(SlamConfig(keyframeMinTranslationM = 0.0, keyframeMinIntervalNs = 1000))
        sel.offer(pose(0))
        assertThat(sel.offer(pose(500, Vector3(10.0, 0.0, 0.0)))).isFalse() // within interval
        assertThat(sel.offer(pose(2000, Vector3(10.0, 0.0, 0.0)))).isTrue()
    }

    @Test
    fun `paused poses never become keyframes`() {
        val sel = KeyframeSelector(SlamConfig())
        assertThat(sel.offer(pose(0, state = TrackingState.PAUSED))).isFalse()
        assertThat(sel.keyframeCount).isEqualTo(0)
    }

    @Test
    fun `pose graph accumulates trajectory length over tracking poses`() {
        val g = PoseGraph()
        g.addPose(pose(0, Vector3(0.0, 0.0, 0.0)))
        g.addPose(pose(1, Vector3(3.0, 0.0, 0.0)))
        g.addPose(pose(2, Vector3(3.0, 4.0, 0.0)))
        assertThat(g.trajectoryLengthM).isWithin(1e-9).of(7.0) // 3 + 4 (consecutive segments)
        assertThat(g.poseCount).isEqualTo(3)
    }

    @Test
    fun `tracking loss breaks the trajectory polyline`() {
        val g = PoseGraph()
        g.addPose(pose(0, Vector3(0.0, 0.0, 0.0)))
        g.addPose(pose(1, Vector3(1.0, 0.0, 0.0)))            // +1
        g.addPose(pose(2, Vector3(100.0, 0.0, 0.0), state = TrackingState.PAUSED)) // gap, not counted
        g.addPose(pose(3, Vector3(101.0, 0.0, 0.0)))          // resume, no jump counted
        g.addPose(pose(4, Vector3(102.0, 0.0, 0.0)))          // +1
        assertThat(g.trajectoryLengthM).isWithin(1e-9).of(2.0)
    }
}
