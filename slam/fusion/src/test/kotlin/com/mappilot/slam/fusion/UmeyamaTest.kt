package com.mappilot.slam.fusion

import com.google.common.truth.Truth.assertThat
import com.mappilot.core.model.EnuPoint
import com.mappilot.core.model.Vector3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Test

class UmeyamaTest {

    /** Rotation about an axis by angle, as a row-major 3×3. */
    private fun rotation(axis: DoubleArray, angle: Double): Mat3 {
        val n = norm(axis)
        val (x, y, z) = doubleArrayOf(axis[0] / n, axis[1] / n, axis[2] / n).toList()
        val c = cos(angle); val s = sin(angle); val t = 1 - c
        return Mat3(
            doubleArrayOf(
                t * x * x + c, t * x * y - s * z, t * x * z + s * y,
                t * x * y + s * z, t * y * y + c, t * y * z - s * x,
                t * x * z - s * y, t * y * z + s * x, t * z * z + c,
            ),
        )
    }

    private fun applyKnown(r: Mat3, scale: Double, t: DoubleArray, p: Vector3): EnuPoint {
        val rv = r.timesVec(p.x, p.y, p.z)
        return EnuPoint(scale * rv[0] + t[0], scale * rv[1] + t[1], scale * rv[2] + t[2])
    }

    @Test
    fun `recovers a known scale rotation and translation exactly`() {
        val rnd = Random(42)
        val src = List(12) { Vector3(rnd.nextDouble(-5.0, 5.0), rnd.nextDouble(-5.0, 5.0), rnd.nextDouble(-5.0, 5.0)) }
        val r = rotation(doubleArrayOf(0.3, 1.0, -0.5), 0.7)
        val scale = 2.5
        val t = doubleArrayOf(10.0, -4.0, 3.0)
        val dst = src.map { applyKnown(r, scale, t, it) }

        val sol = Umeyama.solve(src, dst)!!
        assertThat(sol.scale).isWithin(1e-6).of(scale)
        assertThat(sol.translation[0]).isWithin(1e-5).of(10.0)
        assertThat(sol.translation[1]).isWithin(1e-5).of(-4.0)
        assertThat(sol.rmsErrorM).isLessThan(1e-6)
        // rotation matrix matches element-wise
        for (i in 0..8) assertThat(sol.rotation[i]).isWithin(1e-6).of(r.m[i])
        // recovered rotation is proper (det = +1)
        assertThat(Mat3(sol.rotation).determinant()).isWithin(1e-6).of(1.0)
    }

    @Test
    fun `apply reproduces the target points`() {
        val src = listOf(
            Vector3(0.0, 0.0, 0.0), Vector3(1.0, 0.0, 0.0),
            Vector3(0.0, 1.0, 0.0), Vector3(0.0, 0.0, 1.0),
        )
        val r = rotation(doubleArrayOf(0.0, 0.0, 1.0), Math.PI / 2) // 90° about Z
        val dst = src.map { applyKnown(r, 1.5, doubleArrayOf(2.0, 0.0, 0.0), it) }
        val sol = Umeyama.solve(src, dst)!!
        src.forEachIndexed { i, p ->
            val e = sol.apply(p)
            assertThat(e.east).isWithin(1e-6).of(dst[i].east)
            assertThat(e.north).isWithin(1e-6).of(dst[i].north)
            assertThat(e.up).isWithin(1e-6).of(dst[i].up)
        }
    }

    @Test
    fun `tolerates noise with small residual`() {
        val rnd = Random(7)
        val src = List(50) { Vector3(rnd.nextDouble(-10.0, 10.0), rnd.nextDouble(-10.0, 10.0), rnd.nextDouble(-2.0, 2.0)) }
        val r = rotation(doubleArrayOf(0.1, 0.2, 1.0), 0.4)
        val dst = src.map {
            val e = applyKnown(r, 1.0, doubleArrayOf(5.0, 5.0, 0.0), it)
            EnuPoint(e.east + rnd.nextDouble(-0.05, 0.05), e.north + rnd.nextDouble(-0.05, 0.05), e.up + rnd.nextDouble(-0.05, 0.05))
        }
        val sol = Umeyama.solve(src, dst)!!
        assertThat(sol.scale).isWithin(0.05).of(1.0)
        assertThat(sol.rmsErrorM).isLessThan(0.1)
    }

    @Test
    fun `returns null for too few or degenerate correspondences`() {
        assertThat(Umeyama.solve(listOf(Vector3(0.0, 0.0, 0.0)), listOf(EnuPoint(0.0, 0.0, 0.0)))).isNull()
        // All source points identical → undefined.
        val src = List(5) { Vector3(1.0, 1.0, 1.0) }
        val dst = List(5) { EnuPoint(it.toDouble(), 0.0, 0.0) }
        assertThat(Umeyama.solve(src, dst)).isNull()
    }
}
