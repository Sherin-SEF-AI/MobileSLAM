package com.mappilot.slam.fusion

import com.mappilot.core.model.EnuPoint
import com.mappilot.core.model.Vector3
import kotlin.math.sqrt

/**
 * Similarity transform Y ≈ scale · R · X + t mapping the VIO frame to the local
 * ENU frame. Carries the fit quality (RMS residual) so callers can gate on it
 * rather than trusting an under-constrained alignment.
 */
data class SimilarityTransform(
    val scale: Double,
    val rotation: DoubleArray, // 9, row-major
    val translation: DoubleArray, // 3
    val rmsErrorM: Double,
    val correspondences: Int,
) {
    /** Apply to a VIO-frame point, yielding ENU. */
    fun apply(p: Vector3): EnuPoint {
        val r = Mat3(rotation)
        val rv = r.timesVec(p.x, p.y, p.z)
        return EnuPoint(
            east = scale * rv[0] + translation[0],
            north = scale * rv[1] + translation[1],
            up = scale * rv[2] + translation[2],
        )
    }

    companion object {
        val IDENTITY = SimilarityTransform(
            scale = 1.0,
            rotation = Mat3.identity().m,
            translation = doubleArrayOf(0.0, 0.0, 0.0),
            rmsErrorM = 0.0,
            correspondences = 0,
        )
    }
}

/**
 * Umeyama (1991) least-squares similarity estimation between corresponding 3D
 * point sets. [source] are VIO-frame positions, [target] are ENU positions.
 *
 * Returns null when there are too few correspondences or the source points are
 * degenerate (e.g. collinear — common when the device has barely moved), so the
 * caller surfaces "alignment not yet available" rather than a bogus transform.
 */
object Umeyama {

    fun solve(source: List<Vector3>, target: List<EnuPoint>): SimilarityTransform? {
        require(source.size == target.size) { "point sets differ in size" }
        val n = source.size
        if (n < MIN_CORRESPONDENCES) return null

        val xs = Array(n) { doubleArrayOf(source[it].x, source[it].y, source[it].z) }
        val ys = Array(n) { doubleArrayOf(target[it].east, target[it].north, target[it].up) }

        val meanX = mean(xs)
        val meanY = mean(ys)

        // Covariance Σ = (1/n) Σ (y-ȳ)(x-x̄)ᵀ and source variance σ²_x.
        var cov = Mat3(DoubleArray(9))
        var varX = 0.0
        for (i in 0 until n) {
            val dx = sub(xs[i], meanX)
            val dy = sub(ys[i], meanY)
            cov = add(cov, Mat3.outer(dy, dx))
            varX += dx[0] * dx[0] + dx[1] * dx[1] + dx[2] * dx[2]
        }
        cov = scale(cov, 1.0 / n)
        varX /= n
        if (varX < 1e-9) return null // source points coincident → undefined

        val svd = svd3(cov)
        // S corrects for reflection so R is a proper rotation (det = +1).
        val detUV = (svd.u * svd.v.transpose()).determinant()
        val sCorr = if (detUV < 0) Mat3.diag(1.0, 1.0, -1.0) else Mat3.identity()

        val r = svd.u * sCorr * svd.v.transpose()

        // scale c = trace(D · S) / σ²_x
        val traceDS = svd.s[0] * sCorr[0, 0] + svd.s[1] * sCorr[1, 1] + svd.s[2] * sCorr[2, 2]
        val c = traceDS / varX

        // t = ȳ − c · R · x̄
        val rMeanX = r.timesVec(meanX[0], meanX[1], meanX[2])
        val t = doubleArrayOf(
            meanY[0] - c * rMeanX[0],
            meanY[1] - c * rMeanX[1],
            meanY[2] - c * rMeanX[2],
        )

        // RMS residual over all correspondences.
        var sse = 0.0
        for (i in 0 until n) {
            val rv = r.timesVec(xs[i][0], xs[i][1], xs[i][2])
            val px = c * rv[0] + t[0]
            val py = c * rv[1] + t[1]
            val pz = c * rv[2] + t[2]
            val ex = px - ys[i][0]; val ey = py - ys[i][1]; val ez = pz - ys[i][2]
            sse += ex * ex + ey * ey + ez * ez
        }
        val rms = sqrt(sse / n)

        return SimilarityTransform(c, r.m, t, rms, n)
    }

    private fun mean(p: Array<DoubleArray>): DoubleArray {
        val m = doubleArrayOf(0.0, 0.0, 0.0)
        for (v in p) { m[0] += v[0]; m[1] += v[1]; m[2] += v[2] }
        val n = p.size.toDouble()
        return doubleArrayOf(m[0] / n, m[1] / n, m[2] / n)
    }

    private fun sub(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
    private fun add(a: Mat3, b: Mat3) = Mat3(DoubleArray(9) { a.m[it] + b.m[it] })
    private fun scale(a: Mat3, s: Double) = Mat3(DoubleArray(9) { a.m[it] * s })

    const val MIN_CORRESPONDENCES = 3
}
