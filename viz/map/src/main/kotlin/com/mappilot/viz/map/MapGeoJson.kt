package com.mappilot.viz.map

import com.mappilot.core.model.Asset
import com.mappilot.core.model.GeoPoint

/**
 * Builds GeoJSON for the map layers from real assets/trajectory. Pure and
 * unit-tested so layer data is verifiable independently of the renderer. Empty
 * inputs produce empty (valid) FeatureCollections — never placeholder geometry.
 */
object MapGeoJson {

    fun assetsFeatureCollection(assets: List<Asset>): String {
        val features = assets.joinToString(",") { a ->
            """{"type":"Feature","properties":{"id":${a.id},"class":"${a.assetClass.name}",""" +
                """"confidence":${a.confidence}},"geometry":{"type":"Point","coordinates":[${a.geo.longitude},${a.geo.latitude}]}}"""
        }
        return """{"type":"FeatureCollection","features":[$features]}"""
    }

    fun trajectoryLineString(points: List<GeoPoint>): String {
        val coords = points.joinToString(",") { "[${it.longitude},${it.latitude}]" }
        return """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"kind":"trajectory"},""" +
            """"geometry":{"type":"LineString","coordinates":[$coords]}}]}"""
    }

    /** A minimal MapLibre style with a matte dark background and no external tiles. */
    val DARK_STYLE: String = """
        {"version":8,"name":"mappilot-dark","sources":{},"layers":[
        {"id":"bg","type":"background","paint":{"background-color":"#0A0A0A"}}]}
    """.trimIndent()
}
