package com.mappilot.viz.map

import com.google.common.truth.Truth.assertThat
import com.mappilot.core.model.Asset
import com.mappilot.core.model.AssetClass
import com.mappilot.core.model.BoundingBox
import com.mappilot.core.model.GeoPoint
import org.junit.Test

class MapGeoJsonTest {

    private fun asset(id: Long, cls: AssetClass, lat: Double, lon: Double) = Asset(
        id = id, assetClass = cls, geo = GeoPoint(lat, lon, 920.0),
        box = BoundingBox(0f, 0f, 1f, 1f), confidence = 0.9f,
        sourceFrameId = 0, depthM = 5f, embeddingId = null,
    )

    @Test
    fun `asset feature collection uses lon-lat order and class property`() {
        val json = MapGeoJson.assetsFeatureCollection(
            listOf(asset(1, AssetClass.TRAFFIC_LIGHT, 12.97, 77.59)),
        )
        assertThat(json).contains("\"coordinates\":[77.59,12.97]")
        assertThat(json).contains("\"class\":\"TRAFFIC_LIGHT\"")
        assertThat(json).startsWith("{\"type\":\"FeatureCollection\"")
    }

    @Test
    fun `empty assets produce a valid empty collection`() {
        assertThat(MapGeoJson.assetsFeatureCollection(emptyList()))
            .isEqualTo("{\"type\":\"FeatureCollection\",\"features\":[]}")
    }

    @Test
    fun `trajectory is a linestring in lon-lat order`() {
        val json = MapGeoJson.trajectoryLineString(
            listOf(GeoPoint(12.97, 77.59, 0.0), GeoPoint(12.98, 77.60, 0.0)),
        )
        assertThat(json).contains("\"type\":\"LineString\"")
        assertThat(json).contains("[77.59,12.97],[77.6,12.98]")
    }
}
