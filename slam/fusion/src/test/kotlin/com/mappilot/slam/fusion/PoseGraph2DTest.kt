package com.mappilot.slam.fusion

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.hypot

class PoseGraph2DTest {

    /** True L-shaped path: 10 m east, then 10 m north, 1 m spacing. */
    private fun trueLShape(): List<PoseGraph2D.Node> {
        val nodes = ArrayList<PoseGraph2D.Node>()
        for (i in 0..10) nodes.add(PoseGraph2D.Node(i.toDouble(), 0.0, 0.0))
        for (i in 1..10) nodes.add(PoseGraph2D.Node(10.0, i.toDouble(), Math.PI / 2))
        return nodes
    }

    /** Clean VIO odometry between consecutive true nodes. */
    private fun odometryOf(truth: List<PoseGraph2D.Node>): List<PoseGraph2D.OdomEdge> =
        (0 until truth.size - 1).map { i ->
            val a = truth[i]; val b = truth[i + 1]
            PoseGraph2D.relativeOdometry(a.x, a.y, a.theta, b.x, b.y, b.theta).copy(i = i, infoT = 100.0, infoR = 100.0)
        }

    @Test
    fun `recovers the true trajectory from a drifted initial guess`() {
        val truth = trueLShape()
        val odom = odometryOf(truth)

        // Initial guess: same shape but with growing lateral drift (simulates VIO drift).
        val init = truth.mapIndexed { i, t -> PoseGraph2D.Node(t.x, t.y + 0.5 * i, t.theta) }.toMutableList()
        val initErr = hypot(init.last().x - truth.last().x, init.last().y - truth.last().y)
        assertThat(initErr).isGreaterThan(9.0) // ~10 m off before optimization

        // GPS priors at a few true nodes (endpoints + a couple in between).
        val gps = listOf(0, 5, 10, 15, 20).map { i ->
            PoseGraph2D.GpsPrior(i, truth[i].x, truth[i].y, info = 1.0)
        }

        val result = PoseGraph2D.optimize(init, odom, gps, maxIterations = 30)

        // Every node should be pulled back onto the truth.
        var maxErr = 0.0
        for (i in truth.indices) {
            maxErr = maxOf(maxErr, hypot(result.nodes[i].x - truth[i].x, result.nodes[i].y - truth[i].y))
        }
        assertThat(maxErr).isLessThan(0.2)
        assertThat(result.finalCost).isLessThan(0.5)
    }

    @Test
    fun `distributes drift instead of snapping only the anchored ends`() {
        // 3 collinear nodes; the middle one starts displaced. Odometry says they are
        // evenly spaced; GPS pins the two ends at the truth. The middle must move back.
        val truth = listOf(
            PoseGraph2D.Node(0.0, 0.0, 0.0),
            PoseGraph2D.Node(1.0, 0.0, 0.0),
            PoseGraph2D.Node(2.0, 0.0, 0.0),
        )
        val odom = odometryOf(truth)
        val init = mutableListOf(
            PoseGraph2D.Node(0.0, 0.0, 0.0),
            PoseGraph2D.Node(1.0, 1.0, 0.0), // middle displaced 1 m north
            PoseGraph2D.Node(2.0, 0.0, 0.0),
        )
        val gps = listOf(
            PoseGraph2D.GpsPrior(0, 0.0, 0.0, info = 1000.0),
            PoseGraph2D.GpsPrior(2, 2.0, 0.0, info = 1000.0),
        )
        val result = PoseGraph2D.optimize(init, odom, gps, maxIterations = 30)
        assertThat(result.nodes[1].y).isWithin(0.05).of(0.0) // pulled back to the line
    }

    @Test
    fun `empty graph is a no-op`() {
        val r = PoseGraph2D.optimize(mutableListOf(), emptyList(), emptyList())
        assertThat(r.nodes).isEmpty()
    }
}
