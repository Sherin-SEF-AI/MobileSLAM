package com.mappilot.slam.fusion

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Minimal 3×3 linear algebra for the Umeyama alignment: a row-major [Mat3], a
 * Jacobi eigensolver for symmetric matrices, and a 3×3 SVD derived from it. Pure
 * and dependency-free so the georeferencing math is fully unit-tested.
 */
internal class Mat3(val m: DoubleArray) {
    init { require(m.size == 9) }

    operator fun get(r: Int, c: Int): Double = m[r * 3 + c]

    operator fun times(o: Mat3): Mat3 {
        val r = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) {
            var s = 0.0
            for (k in 0..2) s += this[i, k] * o[k, j]
            r[i * 3 + j] = s
        }
        return Mat3(r)
    }

    fun transpose(): Mat3 = Mat3(
        doubleArrayOf(
            m[0], m[3], m[6],
            m[1], m[4], m[7],
            m[2], m[5], m[8],
        ),
    )

    fun timesVec(x: Double, y: Double, z: Double): DoubleArray = doubleArrayOf(
        m[0] * x + m[1] * y + m[2] * z,
        m[3] * x + m[4] * y + m[5] * z,
        m[6] * x + m[7] * y + m[8] * z,
    )

    fun determinant(): Double =
        m[0] * (m[4] * m[8] - m[5] * m[7]) -
            m[1] * (m[3] * m[8] - m[5] * m[6]) +
            m[2] * (m[3] * m[7] - m[4] * m[6])

    companion object {
        fun identity() = Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))
        fun diag(a: Double, b: Double, c: Double) =
            Mat3(doubleArrayOf(a, 0.0, 0.0, 0.0, b, 0.0, 0.0, 0.0, c))

        /** Outer product a·bᵀ (3×3). */
        fun outer(a: DoubleArray, b: DoubleArray) = Mat3(
            doubleArrayOf(
                a[0] * b[0], a[0] * b[1], a[0] * b[2],
                a[1] * b[0], a[1] * b[1], a[1] * b[2],
                a[2] * b[0], a[2] * b[1], a[2] * b[2],
            ),
        )
    }
}

/** Eigen-decomposition of a symmetric 3×3 matrix via cyclic Jacobi rotations. */
internal data class Eigen(val values: DoubleArray, val vectors: Mat3)

internal fun jacobiEigenSymmetric(input: Mat3, maxSweeps: Int = 50): Eigen {
    val a = input.m.copyOf()
    val v = Mat3.identity().m.copyOf()

    fun off(): Double = a[1] * a[1] + a[2] * a[2] + a[5] * a[5]

    repeat(maxSweeps) {
        if (off() < 1e-30) return@repeat
        // (p,q) ∈ {(0,1),(0,2),(1,2)}
        for ((p, q) in listOf(0 to 1, 0 to 2, 1 to 2)) {
            val apq = a[p * 3 + q]
            if (abs(apq) < 1e-300) continue
            val app = a[p * 3 + p]
            val aqq = a[q * 3 + q]
            val phi = 0.5 * kotlin.math.atan2(2 * apq, aqq - app)
            val c = kotlin.math.cos(phi)
            val s = kotlin.math.sin(phi)
            // Apply rotation J^T A J
            for (k in 0..2) {
                val akp = a[k * 3 + p]
                val akq = a[k * 3 + q]
                a[k * 3 + p] = c * akp - s * akq
                a[k * 3 + q] = s * akp + c * akq
            }
            for (k in 0..2) {
                val apk = a[p * 3 + k]
                val aqk = a[q * 3 + k]
                a[p * 3 + k] = c * apk - s * aqk
                a[q * 3 + k] = s * apk + c * aqk
            }
            for (k in 0..2) {
                val vkp = v[k * 3 + p]
                val vkq = v[k * 3 + q]
                v[k * 3 + p] = c * vkp - s * vkq
                v[k * 3 + q] = s * vkp + c * vkq
            }
        }
    }
    return Eigen(doubleArrayOf(a[0], a[4], a[8]), Mat3(v))
}

/** SVD of an arbitrary 3×3 matrix: A = U · diag(s) · Vᵀ, singular values descending. */
internal data class Svd3(val u: Mat3, val s: DoubleArray, val v: Mat3)

internal fun svd3(a: Mat3): Svd3 {
    // Eigen-decompose AᵀA → V and singular values.
    val ata = a.transpose() * a
    val eig = jacobiEigenSymmetric(ata)

    // Sort eigenpairs by eigenvalue descending.
    val order = (0..2).sortedByDescending { eig.values[it] }
    val s = DoubleArray(3) { sqrt(eig.values[order[it]].coerceAtLeast(0.0)) }
    val vCols = Array(3) { i -> doubleArrayOf(eig.vectors[0, order[i]], eig.vectors[1, order[i]], eig.vectors[2, order[i]]) }
    val v = Mat3(
        doubleArrayOf(
            vCols[0][0], vCols[1][0], vCols[2][0],
            vCols[0][1], vCols[1][1], vCols[2][1],
            vCols[0][2], vCols[1][2], vCols[2][2],
        ),
    )

    // U columns = A·v_i / s_i; fall back to an orthonormal completion for tiny s.
    val uCols = Array(3) { i ->
        val av = a.timesVec(vCols[i][0], vCols[i][1], vCols[i][2])
        if (s[i] > 1e-12) doubleArrayOf(av[0] / s[i], av[1] / s[i], av[2] / s[i]) else av
    }
    // Ensure the third U column is orthonormal even for rank-deficient input.
    if (s[2] <= 1e-12) {
        val c = cross(uCols[0], uCols[1])
        val n = norm(c)
        if (n > 1e-12) uCols[2] = doubleArrayOf(c[0] / n, c[1] / n, c[2] / n)
    }
    val u = Mat3(
        doubleArrayOf(
            uCols[0][0], uCols[1][0], uCols[2][0],
            uCols[0][1], uCols[1][1], uCols[2][1],
            uCols[0][2], uCols[1][2], uCols[2][2],
        ),
    )
    return Svd3(u, s, v)
}

internal fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
    a[1] * b[2] - a[2] * b[1],
    a[2] * b[0] - a[0] * b[2],
    a[0] * b[1] - a[1] * b[0],
)

internal fun norm(a: DoubleArray): Double = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
