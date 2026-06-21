package com.mappilot.slam.fusion

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 2D pose-graph optimizer for drift-corrected georeferencing.
 *
 * A long VIO trajectory drifts, so a single global similarity cannot place it on
 * GPS. This instead keeps the VIO's locally-accurate relative motion as odometry
 * constraints between consecutive keyframes and pulls the whole chain onto the
 * GPS fixes (absolute position priors). Gauss-Newton then distributes the
 * accumulated drift across the graph, yielding a globally consistent trajectory in
 * the local ENU plane (x = east, y = north). Altitude and full SE(3) are out of
 * scope here; planar drift dominates road mapping.
 *
 * Without loop closures the graph is a chain plus unary priors, so the linear
 * system is block-tridiagonal (3x3 blocks) and solves in O(N) via block-Thomas.
 *
 * Pure and deterministic, so it is unit tested against synthetic drift.
 */
object PoseGraph2D {

    /** One keyframe pose (east, north, heading) being solved for. */
    data class Node(var x: Double, var y: Double, var theta: Double)

    /**
     * Relative VIO motion measured from node [i] to node [i+1] (consecutive), in
     * node i's frame. [infoT]/[infoR] are translation/rotation weights (inverse
     * variance); VIO odometry is trusted, so these are high.
     */
    data class OdomEdge(val i: Int, val dx: Double, val dy: Double, val dtheta: Double, val infoT: Double = 1.0, val infoR: Double = 1.0)

    /** Absolute GPS position prior on node [i] (east, north), weight = 1/sigma^2. */
    data class GpsPrior(val i: Int, val x: Double, val y: Double, val info: Double)

    data class Result(val nodes: List<Node>, val iterations: Int, val finalCost: Double)

    fun optimize(
        nodes: MutableList<Node>,
        odom: List<OdomEdge>,
        gps: List<GpsPrior>,
        maxIterations: Int = 25,
        convergeDelta: Double = 1e-6,
    ): Result {
        val n = nodes.size
        if (n == 0) return Result(nodes, 0, 0.0)
        var iter = 0
        var cost = 0.0
        while (iter < maxIterations) {
            // Block-tridiagonal normal equations: D[i] (3x3 diagonal), U[i]=H_{i,i+1}
            // (3x3 super-diagonal, lower = its transpose), b[i] (3-vec rhs).
            val D = Array(n) { DoubleArray(9) }
            val U = Array(if (n > 0) n - 1 else 0) { DoubleArray(9) }
            val b = Array(n) { DoubleArray(3) }
            // Levenberg-style damping for numerical stability + the unobserved gauge.
            for (i in 0 until n) { D[i][0] += LAMBDA; D[i][4] += LAMBDA; D[i][8] += LAMBDA }

            cost = 0.0
            for (e in odom) {
                val a = nodes[e.i]; val c = nodes[e.i + 1]
                val (err, A, B) = odomErrorAndJacobians(a, c, e)
                val om = doubleArrayOf(e.infoT, 0.0, 0.0, 0.0, e.infoT, 0.0, 0.0, 0.0, e.infoR) // diag info
                cost += err[0] * om[0] * err[0] + err[1] * om[4] * err[1] + err[2] * om[8] * err[2]
                // H_ii += A^T Om A ; H_jj += B^T Om B ; H_ij += A^T Om B ; b_i += A^T Om e ; b_j += B^T Om e
                addAtA(D[e.i], A, om)
                addAtA(D[e.i + 1], B, om)
                addAtB(U[e.i], A, om, B)
                addAtE(b[e.i], A, om, err)
                addAtE(b[e.i + 1], B, om, err)
            }
            for (p in gps) {
                val nd = nodes[p.i]
                val ex = nd.x - p.x; val ey = nd.y - p.y
                cost += p.info * (ex * ex + ey * ey)
                // J = [I2 | 0] on (x,y); only the x,y diagonal + rhs are affected.
                D[p.i][0] += p.info; D[p.i][4] += p.info
                b[p.i][0] += p.info * ex; b[p.i][1] += p.info * ey
            }

            // Solve H dx = -b (block-tridiagonal). b currently holds +gradient, so negate.
            for (i in 0 until n) { b[i][0] = -b[i][0]; b[i][1] = -b[i][1]; b[i][2] = -b[i][2] }
            val dx = blockThomas(D, U, b) ?: break

            var maxStep = 0.0
            for (i in 0 until n) {
                nodes[i].x += dx[i][0]; nodes[i].y += dx[i][1]
                nodes[i].theta = normalizeAngle(nodes[i].theta + dx[i][2])
                maxStep = maxOf(maxStep, kotlin.math.abs(dx[i][0]), kotlin.math.abs(dx[i][1]), kotlin.math.abs(dx[i][2]))
            }
            iter++
            if (maxStep < convergeDelta) break
        }
        return Result(nodes, iter, cost)
    }

    // --- SE(2) error + Jacobians for a consecutive odometry edge ---

    private fun odomErrorAndJacobians(a: Node, c: Node, e: OdomEdge): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val ci = cos(a.theta); val si = sin(a.theta)
        val cz = cos(e.dtheta); val sz = sin(e.dtheta)
        val dxw = c.x - a.x; val dyw = c.y - a.y
        // Rt_i (t_j - t_i): relative translation in i's frame.
        val rx = ci * dxw + si * dyw
        val ry = -si * dxw + ci * dyw
        // e_t = Rt_z ( rx - dx, ry - dy ); e_th = wrap(theta_j - theta_i - dtheta)
        val ux = rx - e.dx; val uy = ry - e.dy
        val et0 = cz * ux + sz * uy
        val et1 = -sz * ux + cz * uy
        val eth = normalizeAngle(c.theta - a.theta - e.dtheta)
        val err = doubleArrayOf(et0, et1, eth)

        // A = de/dx_i (3x3), B = de/dx_j (3x3). Rt_z * Rt_i:
        // Rt_z = [[cz, sz],[-sz, cz]], Rt_i = [[ci, si],[-si, ci]]
        // M = Rt_z * Rt_i
        val m00 = cz * ci + sz * (-si); val m01 = cz * si + sz * ci
        val m10 = -sz * ci + cz * (-si); val m11 = -sz * si + cz * ci
        // dRt_i/dtheta_i applied to (dxw,dyw): d/dθ [ci*dxw+si*dyw ; -si*dxw+ci*dyw] = [-si*dxw+ci*dyw ; -ci*dxw-si*dyw] = [ry ; -rx]
        // Rt_z * [ry ; -rx]:
        val jcol2_0 = cz * ry + sz * (-rx)
        val jcol2_1 = -sz * ry + cz * (-rx)
        val A = doubleArrayOf(
            -m00, -m01, jcol2_0,
            -m10, -m11, jcol2_1,
            0.0, 0.0, -1.0,
        )
        val B = doubleArrayOf(
            m00, m01, 0.0,
            m10, m11, 0.0,
            0.0, 0.0, 1.0,
        )
        return Triple(err, A, B)
    }

    // --- block accumulation helpers (3x3 row-major, om = diagonal 3x3) ---

    private fun addAtA(dst: DoubleArray, A: DoubleArray, om: DoubleArray) {
        // dst += A^T * diag(om) * A
        for (r in 0 until 3) for (cc in 0 until 3) {
            var s = 0.0
            for (k in 0 until 3) s += A[k * 3 + r] * om[k * 3 + k] * A[k * 3 + cc]
            dst[r * 3 + cc] += s
        }
    }

    private fun addAtB(dst: DoubleArray, A: DoubleArray, om: DoubleArray, B: DoubleArray) {
        for (r in 0 until 3) for (cc in 0 until 3) {
            var s = 0.0
            for (k in 0 until 3) s += A[k * 3 + r] * om[k * 3 + k] * B[k * 3 + cc]
            dst[r * 3 + cc] += s
        }
    }

    private fun addAtE(dst: DoubleArray, A: DoubleArray, om: DoubleArray, e: DoubleArray) {
        for (r in 0 until 3) {
            var s = 0.0
            for (k in 0 until 3) s += A[k * 3 + r] * om[k * 3 + k] * e[k]
            dst[r] += s
        }
    }

    // --- Block-tridiagonal symmetric solve (block-Thomas), 3x3 blocks ---

    private fun blockThomas(D: Array<DoubleArray>, U: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray>? {
        val n = D.size
        if (n == 1) {
            val inv = inv3(D[0]) ?: return null
            return arrayOf(matVec(inv, b[0]))
        }
        val cPrime = Array(n - 1) { DoubleArray(9) } // m_i^{-1} U_i
        val dPrime = Array(n) { DoubleArray(3) }
        var mInv = inv3(D[0]) ?: return null
        cPrime[0] = matMul(mInv, U[0])
        dPrime[0] = matVec(mInv, b[0])
        for (i in 1 until n) {
            // L_i = U_{i-1}^T ; m = D_i - L_i * cPrime[i-1]
            val Lt = transpose(U[i - 1])
            val m = sub(D[i], matMul(Lt, cPrime[i - 1]))
            mInv = inv3(m) ?: return null
            if (i < n - 1) cPrime[i] = matMul(mInv, U[i])
            val rhs = subVec(b[i], matVec(Lt, dPrime[i - 1]))
            dPrime[i] = matVec(mInv, rhs)
        }
        val x = Array(n) { DoubleArray(3) }
        x[n - 1] = dPrime[n - 1]
        for (i in n - 2 downTo 0) x[i] = subVec(dPrime[i], matVec(cPrime[i], x[i + 1]))
        return x
    }

    // --- 3x3 / vec3 helpers (row-major) ---

    private fun matMul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val o = DoubleArray(9)
        for (r in 0 until 3) for (c in 0 until 3) {
            var s = 0.0; for (k in 0 until 3) s += a[r * 3 + k] * b[k * 3 + c]; o[r * 3 + c] = s
        }
        return o
    }

    private fun matVec(a: DoubleArray, v: DoubleArray): DoubleArray =
        doubleArrayOf(
            a[0] * v[0] + a[1] * v[1] + a[2] * v[2],
            a[3] * v[0] + a[4] * v[1] + a[5] * v[2],
            a[6] * v[0] + a[7] * v[1] + a[8] * v[2],
        )

    private fun transpose(a: DoubleArray): DoubleArray =
        doubleArrayOf(a[0], a[3], a[6], a[1], a[4], a[7], a[2], a[5], a[8])

    private fun sub(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(9) { a[it] - b[it] }
    private fun subVec(a: DoubleArray, b: DoubleArray): DoubleArray = doubleArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])

    private fun inv3(m: DoubleArray): DoubleArray? {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]
        val A = e * i - f * h; val B = -(d * i - f * g); val C = d * h - e * g
        val det = a * A + b * B + c * C
        if (kotlin.math.abs(det) < 1e-12) return null
        val inv = 1.0 / det
        return doubleArrayOf(
            A * inv, (c * h - b * i) * inv, (b * f - c * e) * inv,
            B * inv, (a * i - c * g) * inv, (c * d - a * f) * inv,
            C * inv, (b * g - a * h) * inv, (a * e - b * d) * inv,
        )
    }

    fun normalizeAngle(a: Double): Double {
        var x = a
        while (x > Math.PI) x -= 2 * Math.PI
        while (x < -Math.PI) x += 2 * Math.PI
        return x
    }

    /** Relative SE(2) odometry (dx,dy,dtheta) from VIO world poses a -> b, in a's frame. */
    fun relativeOdometry(ax: Double, ay: Double, atheta: Double, bx: Double, by: Double, btheta: Double): OdomEdge {
        val ci = cos(atheta); val si = sin(atheta)
        val dxw = bx - ax; val dyw = by - ay
        val dx = ci * dxw + si * dyw
        val dy = -si * dxw + ci * dyw
        return OdomEdge(0, dx, dy, normalizeAngle(btheta - atheta))
    }

    private const val LAMBDA = 1e-6
}
