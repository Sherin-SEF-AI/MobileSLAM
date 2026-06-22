package com.mappilot.slam.fusion

import com.google.common.truth.Truth.assertThat
import com.mappilot.core.model.EnuPoint
import com.mappilot.core.model.GeoPoint
import com.mappilot.core.model.Vector3
import org.junit.Test
import kotlin.math.hypot

class TrajectoryRefinerTest {

    private val enuFrame = EnuFrame(GeoPoint(12.97, 77.59, 900.0))

    @Test
    fun `recovers a true L-path from an offset initial guess`() {
        // True ENU L-path: 5 m east, then 5 m north.
        val truth = ArrayList<Pair<Double, Double>>()
        for (e in 0..5) truth.add(e.toDouble() to 0.0)
        for (n in 1..5) truth.add(5.0 to n.toDouble())

        // VIO positions: ground plane (x, -z) == ENU planar here. ts = i seconds.
        val vio = truth.mapIndexed { i, (e, n) ->
            TrajectoryRefiner.TimedPos(i * 1_000_000_000L, Vector3(e, 0.0, -n))
        }
        // GPS priors at several true positions.
        val fixes = listOf(0, 3, 5, 8, 10).map { i ->
            val (e, n) = truth[i]
            TrajectoryRefiner.TimedGeo(i * 1_000_000_000L, enuFrame.toGeo(EnuPoint(e, n, 0.0)), accuracyM = 2f)
        }
        // Online transform was off by a global (10, -5) m translation: the drift to remove.
        val initTransform = SimilarityTransform.IDENTITY.copy(translation = doubleArrayOf(10.0, -5.0, 0.0))

        val refined = TrajectoryRefiner.refine(vio, fixes, enuFrame, initTransform)

        assertThat(refined).hasSize(truth.size)
        var maxErr = 0.0
        refined.forEachIndexed { i, g ->
            val e = enuFrame.toEnu(g)
            val (te, tn) = truth[i]
            maxErr = maxOf(maxErr, hypot(e.east - te, e.north - tn))
        }
        assertThat(maxErr).isLessThan(0.3)
    }

    @Test
    fun `returns empty without enough anchors`() {
        val vio = (0..5).map { TrajectoryRefiner.TimedPos(it * 1_000_000_000L, Vector3(it.toDouble(), 0.0, 0.0)) }
        val oneFix = listOf(TrajectoryRefiner.TimedGeo(0, enuFrame.toGeo(EnuPoint(0.0, 0.0, 0.0)), 2f))
        assertThat(TrajectoryRefiner.refine(vio, oneFix, enuFrame, SimilarityTransform.IDENTITY)).isEmpty()
    }
}
