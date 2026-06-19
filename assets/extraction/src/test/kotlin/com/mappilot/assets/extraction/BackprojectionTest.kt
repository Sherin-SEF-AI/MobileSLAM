package com.mappilot.assets.extraction

import com.google.common.truth.Truth.assertThat
import com.mappilot.core.model.CameraIntrinsics
import com.mappilot.core.model.Quaternion
import com.mappilot.core.model.Vector3
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Test

class BackprojectionTest {

    private val intr = CameraIntrinsics(
        fx = 1000.0, fy = 1000.0, cx = 960.0, cy = 540.0,
        imageWidth = 1920, imageHeight = 1080,
    )

    @Test
    fun `center pixel projects straight ahead`() {
        val p = Backprojection.cameraPoint(960.0, 540.0, depthM = 5.0, intr)
        assertThat(p.x).isWithin(1e-9).of(0.0)
        assertThat(p.y).isWithin(1e-9).of(0.0)
        assertThat(p.z).isWithin(1e-9).of(5.0)
    }

    @Test
    fun `offset pixel has expected camera coordinates`() {
        // 100 px right, 50 px down of principal point, 2 m depth.
        val p = Backprojection.cameraPoint(1060.0, 590.0, depthM = 2.0, intr)
        assertThat(p.x).isWithin(1e-9).of(100.0 / 1000.0 * 2.0) // 0.2
        assertThat(p.y).isWithin(1e-9).of(50.0 / 1000.0 * 2.0)  // 0.1
        assertThat(p.z).isWithin(1e-9).of(2.0)
    }

    @Test
    fun `identity pose places point at camera coordinates plus position`() {
        val world = Backprojection.backproject(
            960.0, 540.0, 7.0, intr,
            position = Vector3(10.0, 20.0, 30.0),
            orientation = Quaternion.IDENTITY,
        )!!
        assertThat(world.x).isWithin(1e-9).of(10.0)
        assertThat(world.y).isWithin(1e-9).of(20.0)
        assertThat(world.z).isWithin(1e-9).of(37.0)
    }

    @Test
    fun `yaw rotation rotates the forward ray`() {
        // 90° about +Y maps camera +z to world +x.
        val h = Math.toRadians(90.0) / 2
        val q = Quaternion(0.0, sin(h), 0.0, cos(h))
        val world = Backprojection.backproject(960.0, 540.0, 4.0, intr, Vector3.ZERO, q)!!
        assertThat(world.x).isWithin(1e-6).of(4.0)
        assertThat(world.z).isWithin(1e-6).of(0.0)
    }

    @Test
    fun `non-positive depth yields null - no fabricated placement`() {
        assertThat(Backprojection.backproject(960.0, 540.0, 0.0, intr, Vector3.ZERO, Quaternion.IDENTITY)).isNull()
        assertThat(Backprojection.backproject(960.0, 540.0, Double.NaN, intr, Vector3.ZERO, Quaternion.IDENTITY)).isNull()
    }

    @Test
    fun `rotate preserves vector length`() {
        val h = Math.toRadians(37.0) / 2
        // Unit axis (0.6, 0.0, 0.8) → unit quaternion.
        val q = Quaternion(sin(h) * 0.6, 0.0, sin(h) * 0.8, cos(h))
        val v = Vector3(1.0, 2.0, 3.0)
        val r = Backprojection.rotate(q, v)
        val lenV = Math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z)
        val lenR = Math.sqrt(r.x * r.x + r.y * r.y + r.z * r.z)
        assertThat(lenR).isWithin(1e-6).of(lenV)
    }
}
