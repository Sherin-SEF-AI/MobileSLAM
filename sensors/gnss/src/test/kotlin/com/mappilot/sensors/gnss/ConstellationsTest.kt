package com.mappilot.sensors.gnss

import android.location.GnssStatus
import com.google.common.truth.Truth.assertThat
import com.mappilot.core.model.Constellation
import org.junit.Test

class ConstellationsTest {

    @Test
    fun `maps all six constellations including NavIC`() {
        assertThat(GnssStatus.CONSTELLATION_GPS.toConstellation()).isEqualTo(Constellation.GPS)
        assertThat(GnssStatus.CONSTELLATION_GLONASS.toConstellation()).isEqualTo(Constellation.GLONASS)
        assertThat(GnssStatus.CONSTELLATION_GALILEO.toConstellation()).isEqualTo(Constellation.GALILEO)
        assertThat(GnssStatus.CONSTELLATION_BEIDOU.toConstellation()).isEqualTo(Constellation.BEIDOU)
        assertThat(GnssStatus.CONSTELLATION_QZSS.toConstellation()).isEqualTo(Constellation.QZSS)
        assertThat(GnssStatus.CONSTELLATION_IRNSS.toConstellation()).isEqualTo(Constellation.IRNSS)
    }

    @Test
    fun `unknown constellation maps to UNKNOWN not a guessed value`() {
        assertThat(GnssStatus.CONSTELLATION_UNKNOWN.toConstellation()).isEqualTo(Constellation.UNKNOWN)
        assertThat(999.toConstellation()).isEqualTo(Constellation.UNKNOWN)
    }
}
