package com.mappilot.slam.fusion

import com.google.common.truth.Truth.assertThat
import com.mappilot.core.model.GeoPoint
import kotlin.math.hypot
import org.junit.Test

class GeodesyTest {

    private val origin = GeoPoint(12.9716, 77.5946, 920.0) // Bengaluru

    @Test
    fun `origin maps to ENU zero`() {
        val enu = EnuFrame(origin).toEnu(origin)
        assertThat(enu.east).isWithin(1e-6).of(0.0)
        assertThat(enu.north).isWithin(1e-6).of(0.0)
        assertThat(enu.up).isWithin(1e-4).of(0.0)
    }

    @Test
    fun `one degree of latitude north is about 111 km north and near-zero east`() {
        val frame = EnuFrame(origin)
        val north1deg = frame.toEnu(GeoPoint(origin.latitude + 1.0, origin.longitude, origin.altitude))
        assertThat(north1deg.north).isWithin(2_000.0).of(110_574.0) // ~meridian length at this lat
        assertThat(kotlin.math.abs(north1deg.east)).isLessThan(1.0)
    }

    @Test
    fun `small eastward offset has expected magnitude`() {
        val frame = EnuFrame(origin)
        // ~0.001° lon east at this latitude ≈ cos(lat) * 111320 * 0.001 m
        val enu = frame.toEnu(GeoPoint(origin.latitude, origin.longitude + 0.001, origin.altitude))
        val expected = Math.cos(Math.toRadians(origin.latitude)) * 111_320.0 * 0.001
        assertThat(enu.east).isWithin(2.0).of(expected)
        assertThat(kotlin.math.abs(enu.north)).isLessThan(1.0)
    }

    @Test
    fun `geodetic to enu round-trips`() {
        val frame = EnuFrame(origin)
        val p = GeoPoint(12.9802, 77.6100, 935.0)
        val back = frame.toGeo(frame.toEnu(p))
        assertThat(back.latitude).isWithin(1e-7).of(p.latitude)
        assertThat(back.longitude).isWithin(1e-7).of(p.longitude)
        assertThat(back.altitude).isWithin(0.1).of(p.altitude)
    }

    @Test
    fun `enu horizontal distance matches haversine within tolerance`() {
        val frame = EnuFrame(origin)
        val p = GeoPoint(12.9802, 77.6100, 920.0)
        val enu = frame.toEnu(p)
        val enuDist = hypot(enu.east, enu.north)
        // Haversine reference
        val rEarth = 6_371_000.0
        val dLat = Math.toRadians(p.latitude - origin.latitude)
        val dLon = Math.toRadians(p.longitude - origin.longitude)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(origin.latitude)) * Math.cos(Math.toRadians(p.latitude)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val haversine = 2 * rEarth * Math.asin(Math.sqrt(a))
        assertThat(enuDist).isWithin(haversine * 0.01).of(haversine)
    }
}
