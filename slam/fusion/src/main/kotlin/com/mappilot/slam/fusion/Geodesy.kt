package com.mappilot.slam.fusion

import com.mappilot.core.model.EnuPoint
import com.mappilot.core.model.GeoPoint
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WGS84 geodetic ⇄ ECEF ⇄ local-ENU conversions. The ENU frame is anchored at a
 * session origin (the first good GNSS fix); all georeferenced poses are expressed
 * relative to it.
 */
class EnuFrame(val origin: GeoPoint) {

    private val lat0 = Math.toRadians(origin.latitude)
    private val lon0 = Math.toRadians(origin.longitude)
    private val originEcef = geodeticToEcef(origin)
    private val sinLat = sin(lat0)
    private val cosLat = cos(lat0)
    private val sinLon = sin(lon0)
    private val cosLon = cos(lon0)

    fun toEnu(p: GeoPoint): EnuPoint {
        val e = geodeticToEcef(p)
        val dx = e[0] - originEcef[0]
        val dy = e[1] - originEcef[1]
        val dz = e[2] - originEcef[2]
        return EnuPoint(
            east = -sinLon * dx + cosLon * dy,
            north = -sinLat * cosLon * dx - sinLat * sinLon * dy + cosLat * dz,
            up = cosLat * cosLon * dx + cosLat * sinLon * dy + sinLat * dz,
        )
    }

    fun toGeo(p: EnuPoint): GeoPoint {
        // ENU → ECEF delta (transpose of the ENU rotation), then ECEF → geodetic.
        val dx = -sinLon * p.east - sinLat * cosLon * p.north + cosLat * cosLon * p.up
        val dy = cosLon * p.east - sinLat * sinLon * p.north + cosLat * sinLon * p.up
        val dz = cosLat * p.north + sinLat * p.up
        return ecefToGeodetic(
            originEcef[0] + dx,
            originEcef[1] + dy,
            originEcef[2] + dz,
        )
    }

    companion object {
        const val A = 6_378_137.0 // semi-major axis (m)
        const val F = 1.0 / 298.257223563 // flattening
        val E2 = F * (2 - F) // first eccentricity squared

        fun geodeticToEcef(p: GeoPoint): DoubleArray {
            val lat = Math.toRadians(p.latitude)
            val lon = Math.toRadians(p.longitude)
            val sinLat = sin(lat)
            val n = A / sqrt(1 - E2 * sinLat * sinLat)
            val h = if (p.altitude.isNaN()) 0.0 else p.altitude
            return doubleArrayOf(
                (n + h) * cos(lat) * cos(lon),
                (n + h) * cos(lat) * sin(lon),
                (n * (1 - E2) + h) * sinLat,
            )
        }

        /** Bowring's method for ECEF → geodetic. */
        fun ecefToGeodetic(x: Double, y: Double, z: Double): GeoPoint {
            val lon = kotlin.math.atan2(y, x)
            val p = sqrt(x * x + y * y)
            val b = A * (1 - F)
            val ep2 = (A * A - b * b) / (b * b)
            val th = kotlin.math.atan2(A * z, b * p)
            val sinTh = sin(th); val cosTh = cos(th)
            val lat = kotlin.math.atan2(
                z + ep2 * b * sinTh * sinTh * sinTh,
                p - E2 * A * cosTh * cosTh * cosTh,
            )
            val sinLat = sin(lat)
            val n = A / sqrt(1 - E2 * sinLat * sinLat)
            val alt = p / cos(lat) - n
            return GeoPoint(Math.toDegrees(lat), Math.toDegrees(lon), alt)
        }
    }
}
