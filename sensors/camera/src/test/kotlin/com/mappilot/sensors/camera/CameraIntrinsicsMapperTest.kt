package com.mappilot.sensors.camera

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CameraIntrinsicsMapperTest {

    @Test
    fun `scales intrinsics from active array to output resolution`() {
        // Calibration in a 4000x3000 array; frames delivered at 2000x1500 (0.5x).
        val intr = CameraIntrinsicsMapper.map(
            calibration = floatArrayOf(3200f, 3200f, 2000f, 1500f, 0f),
            distortion = floatArrayOf(0.1f, -0.2f, 0.05f, 0.001f, -0.002f),
            arrayWidth = 4000,
            arrayHeight = 3000,
            outputWidth = 2000,
            outputHeight = 1500,
        )!!
        assertThat(intr.fx).isWithin(1e-6).of(1600.0)
        assertThat(intr.fy).isWithin(1e-6).of(1600.0)
        assertThat(intr.cx).isWithin(1e-6).of(1000.0)
        assertThat(intr.cy).isWithin(1e-6).of(750.0)
        assertThat(intr.imageWidth).isEqualTo(2000)
    }

    @Test
    fun `maps android distortion order to opencv order`() {
        val intr = CameraIntrinsicsMapper.map(
            calibration = floatArrayOf(1000f, 1000f, 500f, 500f, 0f),
            distortion = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f), // κ1..κ5
            arrayWidth = 1000, arrayHeight = 1000,
            outputWidth = 1000, outputHeight = 1000,
        )!!
        assertThat(intr.k1).isWithin(1e-6).of(0.1) // κ1
        assertThat(intr.k2).isWithin(1e-6).of(0.2) // κ2
        assertThat(intr.k3).isWithin(1e-6).of(0.3) // κ3
        assertThat(intr.p1).isWithin(1e-6).of(0.4) // κ4 -> p1
        assertThat(intr.p2).isWithin(1e-6).of(0.5) // κ5 -> p2
    }

    @Test
    fun `returns null when calibration absent - no fabricated pinhole`() {
        assertThat(
            CameraIntrinsicsMapper.map(null, null, 1000, 1000, 1000, 1000),
        ).isNull()
    }
}
