package com.mappilot.geo.trajectory

import com.google.common.truth.Truth.assertThat
import com.mappilot.core.model.GeoPoint
import org.junit.Test

class TrajectoryBuilderTest {

    private fun pt(ts: Long, lat: Double, lon: Double, e: Double, n: Double) =
        TrajectoryPoint(ts, GeoPoint(lat, lon, 920.0), e, n, 0.0)

    @Test
    fun `length is planimetric enu path`() {
        val b = TrajectoryBuilder()
        b.add(pt(0, 12.0, 77.0, 0.0, 0.0))
        b.add(pt(1, 12.0, 77.0, 3.0, 0.0))
        b.add(pt(2, 12.0, 77.0, 3.0, 4.0))
        assertThat(b.lengthM()).isWithin(1e-9).of(7.0)
    }

    @Test
    fun `geojson is a well-formed linestring with lon-lat order`() {
        val b = TrajectoryBuilder()
        b.add(pt(0, 12.97, 77.59, 0.0, 0.0))
        b.add(pt(1, 12.98, 77.60, 10.0, 10.0))
        val json = b.toGeoJson()
        assertThat(json).contains("\"type\":\"LineString\"")
        assertThat(json).contains("[77.59,12.97,920.0]") // GeoJSON is [lon,lat,alt]
        assertThat(json).contains("\"points\":2")
    }

    @Test
    fun `csv has header and one row per point`() {
        val b = TrajectoryBuilder()
        b.add(pt(100, 12.97, 77.59, 1.0, 2.0))
        val csv = b.toCsv().trim().lines()
        assertThat(csv).hasSize(2)
        assertThat(csv[0]).isEqualTo("timestamp_ns,lat,lon,alt,east,north,up")
        assertThat(csv[1]).startsWith("100,12.97,77.59,920.0,1.0,2.0")
    }
}
