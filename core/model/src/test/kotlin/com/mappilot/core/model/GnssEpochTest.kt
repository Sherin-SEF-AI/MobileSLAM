package com.mappilot.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GnssEpochTest {

    private fun sat(cn0: Float, used: Boolean) = GnssSatellite(
        svid = 1,
        constellation = Constellation.GPS,
        cn0DbHz = cn0,
        usedInFix = used,
        azimuthDeg = 0f,
        elevationDeg = 45f,
    )

    @Test
    fun `epoch summarizes used and visible counts`() {
        val epoch = GnssEpoch(
            timestampNs = 1_000L,
            fix = null,
            satellites = listOf(sat(40f, true), sat(20f, false), sat(30f, true)),
            rawMeasurements = emptyList(),
        )
        assertThat(epoch.satellitesVisible).isEqualTo(3)
        assertThat(epoch.satellitesUsed).isEqualTo(2)
        assertThat(epoch.meanCn0).isWithin(0.01f).of(30f)
    }

    @Test
    fun `empty epoch reports zero mean cn0 not a fabricated value`() {
        val epoch = GnssEpoch(1L, null, emptyList(), emptyList())
        assertThat(epoch.meanCn0).isEqualTo(0f)
        assertThat(epoch.satellitesVisible).isEqualTo(0)
    }
}
