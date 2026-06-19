package com.mappilot.sensors.gnss

import android.location.GnssStatus
import com.mappilot.core.model.Constellation

/** Maps Android [GnssStatus] constellation constants to the domain enum. */
internal fun Int.toConstellation(): Constellation = when (this) {
    GnssStatus.CONSTELLATION_GPS -> Constellation.GPS
    GnssStatus.CONSTELLATION_GLONASS -> Constellation.GLONASS
    GnssStatus.CONSTELLATION_GALILEO -> Constellation.GALILEO
    GnssStatus.CONSTELLATION_BEIDOU -> Constellation.BEIDOU
    GnssStatus.CONSTELLATION_QZSS -> Constellation.QZSS
    GnssStatus.CONSTELLATION_IRNSS -> Constellation.IRNSS // NavIC
    GnssStatus.CONSTELLATION_SBAS -> Constellation.SBAS
    else -> Constellation.UNKNOWN
}
