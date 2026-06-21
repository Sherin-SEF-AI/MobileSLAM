package com.mappilot.slam.fusion

import com.google.common.truth.Truth.assertThat
import com.mappilot.core.common.bus.SharedFlowEventBus
import com.mappilot.core.model.EnuPoint
import com.mappilot.core.model.GeoPoint
import com.mappilot.core.model.Pose
import com.mappilot.core.model.Quaternion
import com.mappilot.core.model.TrackingFailureReason
import com.mappilot.core.model.TrackingState
import com.mappilot.core.model.Vector3
import org.junit.Test

/**
 * Regression for "No georeferenced data": with a strong outdoor fix the alignment
 * must succeed despite consumer-grade GPS noise. The old 5 m RMS gate sat below the
 * phone-GPS noise floor, so it rejected every real fix and the map stayed empty.
 */
class GnssVioFusionGpsTest {

    @Test
    fun `aligns under realistic GPS noise that the old 5m gate rejected`() {
        val f = GnssVioFusion(SharedFlowEventBus())
        val origin = GeoPoint(12.97, 77.59, 900.0)
        val frame = EnuFrame(origin)
        val rng = kotlin.random.Random(42)

        // An L-shaped walk (a turn gives the geometry the similarity solve needs).
        val path = ArrayList<Vector3>()
        for (i in 0 until 30) path.add(Vector3(i.toDouble(), 0.0, 0.0))
        for (i in 1..30) path.add(Vector3(29.0, i.toDouble(), 0.0))

        var t = 0L
        for (p in path) {
            t += 1_000_000_000L
            f.onPose(Pose(t, p, Quaternion(0.0, 0.0, 0.0, 1.0), TrackingState.TRACKING, TrackingFailureReason.NONE, 1f))
            // GPS = true position + about +/-8 m noise, expressed in ENU then geo.
            val nx = p.x + (rng.nextDouble() - 0.5) * 16.0
            val ny = p.y + (rng.nextDouble() - 0.5) * 16.0
            f.onGnssFix(frame.toGeo(EnuPoint(nx, ny, p.z)), t, hAccuracyM = 8f)
        }

        assertThat(f.state.value.aligned).isTrue()
        assertThat(f.state.value.rmsErrorM).isLessThan(18.0) // accepted by the new gate
        assertThat(f.state.value.rmsErrorM).isGreaterThan(3.0) // genuinely noisy: the old 5 m gate would reject
    }
}
