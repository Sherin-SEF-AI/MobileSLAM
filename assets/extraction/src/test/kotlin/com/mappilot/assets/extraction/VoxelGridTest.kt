package com.mappilot.assets.extraction

import com.google.common.truth.Truth.assertThat
import com.mappilot.core.model.Landmark
import com.mappilot.core.model.Vector3
import org.junit.Test

class VoxelGridTest {

    private fun lm(id: Long, x: Double, y: Double, z: Double, conf: Float) =
        Landmark(id = id, position = Vector3(x, y, z), geo = null, confidence = conf)

    @Test
    fun `collapses points in the same cell keeping the highest confidence`() {
        val points = listOf(
            lm(1, 0.01, 0.01, 0.01, 0.4f), // same 0.1 m cell ...
            lm(2, 0.05, 0.02, 0.03, 0.9f), // ... as this (higher conf wins)
            lm(3, 5.0, 5.0, 5.0, 0.7f), // far cell
        )
        val out = VoxelGrid.downsample(points, voxelSizeM = 0.1)
        assertThat(out).hasSize(2)
        assertThat(out.first { it.position.x < 1.0 }.id).isEqualTo(2L) // 0.9 conf beat 0.4
    }

    @Test
    fun `negative cells are handled and distinct from positive`() {
        val points = listOf(
            lm(1, -0.05, 0.0, 0.0, 0.5f),
            lm(2, 0.05, 0.0, 0.0, 0.5f),
        )
        // -0.05 and 0.05 fall in different cells (floor(-0.5)=-1 vs floor(0.5)=0).
        assertThat(VoxelGrid.downsample(points, voxelSizeM = 0.1)).hasSize(2)
    }

    @Test
    fun `degenerate voxel size returns input unchanged`() {
        val points = listOf(lm(1, 0.0, 0.0, 0.0, 0.5f), lm(2, 0.01, 0.0, 0.0, 0.5f))
        assertThat(VoxelGrid.downsample(points, voxelSizeM = 0.0)).hasSize(2)
    }
}
