package com.mappilot.viz.render3d

import com.google.common.truth.Truth.assertThat
import com.mappilot.core.model.Keyframe
import com.mappilot.core.model.Landmark
import com.mappilot.core.model.Pose
import com.mappilot.core.model.Quaternion
import com.mappilot.core.model.TrackingFailureReason
import com.mappilot.core.model.TrackingState
import com.mappilot.core.model.Vector3
import org.junit.Test

class PointCloudSceneTest {

    private fun lm(x: Double, y: Double, z: Double, c: Float = 0.5f) =
        Landmark(0, Vector3(x, y, z), null, c)

    @Test
    fun `centroid is the mean position`() {
        val c = PointCloudScene.centroid(listOf(lm(0.0, 0.0, 0.0), lm(2.0, 4.0, 6.0)))
        assertThat(c.x).isWithin(1e-9).of(1.0)
        assertThat(c.y).isWithin(1e-9).of(2.0)
        assertThat(c.z).isWithin(1e-9).of(3.0)
    }

    @Test
    fun `point vertices are centred and carry confidence`() {
        val verts = PointCloudScene.pointVertices(listOf(lm(5.0, 5.0, 5.0, 0.8f)), Vector3(5.0, 5.0, 5.0))
        assertThat(verts.size).isEqualTo(4)
        assertThat(verts[0]).isWithin(1e-6f).of(0f) // centred → 0
        assertThat(verts[3]).isWithin(1e-6f).of(0.8f) // confidence
    }

    @Test
    fun `bounding radius is the farthest landmark from the centroid`() {
        val pts = listOf(lm(0.0, 0.0, 0.0), lm(3.0, 4.0, 0.0)) // centroid (1.5,2,0)
        val c = PointCloudScene.centroid(pts)
        // each point is 2.5 from the centroid (half of the 5-length 3-4-5 segment).
        assertThat(PointCloudScene.boundingRadius(pts, c)).isWithin(1e-4f).of(2.5f)
        assertThat(PointCloudScene.boundingRadius(emptyList(), c)).isEqualTo(0f)
    }

    @Test
    fun `frustum has 48 floats per keyframe`() {
        val kf = Keyframe(
            frameId = 0, timestampNs = 0,
            pose = Pose(0, Vector3.ZERO, Quaternion.IDENTITY, TrackingState.TRACKING, TrackingFailureReason.NONE, 1f),
            enuPose = null, intrinsics = null,
        )
        val lines = PointCloudScene.frustumLines(listOf(kf), Vector3.ZERO)
        assertThat(lines.size).isEqualTo(48) // 8 segments * 2 vertices * 3 floats
    }

    @Test
    fun `identity-pose frustum apex sits at the keyframe position`() {
        val kf = Keyframe(
            frameId = 0, timestampNs = 0,
            pose = Pose(0, Vector3(1.0, 2.0, 3.0), Quaternion.IDENTITY, TrackingState.TRACKING, TrackingFailureReason.NONE, 1f),
            enuPose = null, intrinsics = null,
        )
        val lines = PointCloudScene.frustumLines(listOf(kf), Vector3.ZERO)
        // First segment starts at the apex = position (centroid 0).
        assertThat(lines[0]).isWithin(1e-5f).of(1f)
        assertThat(lines[1]).isWithin(1e-5f).of(2f)
        assertThat(lines[2]).isWithin(1e-5f).of(3f)
    }
}
