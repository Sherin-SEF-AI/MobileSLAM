package com.mappilot.slam.fusion

import com.google.common.truth.Truth.assertThat
import com.mappilot.core.common.bus.SharedFlowEventBus
import com.mappilot.core.model.EnuPoint
import com.mappilot.core.model.GeoCorrespondence
import com.mappilot.core.model.GeoPoint
import com.mappilot.core.model.Vector3
import org.junit.Test
import kotlin.math.abs

/**
 * Verifies the ARCore Geospatial (VPS) fusion path: exact VIO<->WGS84
 * correspondences must solve a transform that maps any VIO point back to the
 * matching WGS84 position, and VPS must take precedence over GPS.
 */
class GnssVioFusionGeospatialTest {

    private fun fusion() = GnssVioFusion(SharedFlowEventBus())

    @Test
    fun `solves a transform that round-trips VIO points to WGS84`() {
        val f = fusion()
        val origin = GeoPoint(12.97, 77.59, 900.0)
        val frame = EnuFrame(origin)
        // VIO frame == ENU here, so each VIO point's "geo" is the ENU->geo of itself.
        fun geoOf(e: Double, n: Double, u: Double) = frame.toGeo(EnuPoint(e, n, u))
        val correspondences = listOf(
            GeoCorrespondence(Vector3(0.0, 0.0, 0.0), origin),
            GeoCorrespondence(Vector3(5.0, 0.0, 0.0), geoOf(5.0, 0.0, 0.0)),
            GeoCorrespondence(Vector3(0.0, 5.0, 0.0), geoOf(0.0, 5.0, 0.0)),
            GeoCorrespondence(Vector3(0.0, 0.0, 5.0), geoOf(0.0, 0.0, 5.0)),
        )

        f.onGeospatial(correspondences, hAccuracyM = 1.0f, headingAccuracyDeg = 2.0f)

        assertThat(f.state.value.aligned).isTrue()
        assertThat(f.state.value.vps).isTrue()
        val t = f.currentTransform()!!
        val enu = t.apply(Vector3(3.0, 4.0, 2.0))
        val geo = f.originFrame()!!.toGeo(enu)
        val expected = geoOf(3.0, 4.0, 2.0)
        assertThat(abs(geo.latitude - expected.latitude)).isLessThan(1e-6)
        assertThat(abs(geo.longitude - expected.longitude)).isLessThan(1e-6)
    }

    @Test
    fun `VPS takes precedence over GPS`() {
        val f = fusion()
        val origin = GeoPoint(12.97, 77.59, 900.0)
        val frame = EnuFrame(origin)
        fun geoOf(e: Double, n: Double, u: Double) = frame.toGeo(EnuPoint(e, n, u))
        f.onGeospatial(
            listOf(
                GeoCorrespondence(Vector3(0.0, 0.0, 0.0), origin),
                GeoCorrespondence(Vector3(5.0, 0.0, 0.0), geoOf(5.0, 0.0, 0.0)),
                GeoCorrespondence(Vector3(0.0, 5.0, 0.0), geoOf(0.0, 5.0, 0.0)),
                GeoCorrespondence(Vector3(0.0, 0.0, 5.0), geoOf(0.0, 0.0, 5.0)),
            ),
            hAccuracyM = 1.0f, headingAccuracyDeg = 2.0f,
        )
        val idBefore = f.state.value.transformId
        // A GPS fix must not perturb the live VPS transform.
        f.onGnssFix(GeoPoint(13.0, 77.6, 901.0), fixTimestampNs = 1, hAccuracyM = 3.0f)
        assertThat(f.state.value.vps).isTrue()
        assertThat(f.state.value.transformId).isEqualTo(idBefore)
    }
}
