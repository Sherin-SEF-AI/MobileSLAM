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
        // StringBuilder (sized ~120 B/feature) — avoids the intermediate per-feature
        // String each joinToString lambda would otherwise allocate for 10k+ assets.
        val sb = StringBuilder(32 + assets.size * 120)
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
        for (i in assets.indices) {
            val a = assets[i]
            if (i > 0) sb.append(',')
            sb.append("{\"type\":\"Feature\",\"properties\":{\"id\":").append(a.id)
                .append(",\"class\":\"").append(a.assetClass.name)
                .append("\",\"confidence\":").append(a.confidence)
                .append("},\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
                .append(a.geo.longitude).append(',').append(a.geo.latitude).append("]}}")
        }
        sb.append("]}")
        return sb.toString()
    }

    fun trajectoryLineString(points: List<GeoPoint>): String {
        val sb = StringBuilder(96 + points.size * 24)
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{\"kind\":\"trajectory\"},")
            .append("\"geometry\":{\"type\":\"LineString\",\"coordinates\":[")
        for (i in points.indices) {
            val p = points[i]
            if (i > 0) sb.append(',')
            sb.append('[').append(p.longitude).append(',').append(p.latitude).append(']')
        }
        sb.append("]}}]}")
        return sb.toString()
    }

    /** One LineString feature per trip — for the multi-session Map Explorer. */
    fun trajectoriesFeatureCollection(trajectories: List<List<GeoPoint>>): String {
        val sb = StringBuilder(32 + trajectories.sumOf { it.size } * 24)
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
        var first = true
        for (line in trajectories) {
            if (line.size < 2) continue
            if (!first) sb.append(',')
            first = false
            sb.append("{\"type\":\"Feature\",\"properties\":{\"kind\":\"trajectory\"},")
                .append("\"geometry\":{\"type\":\"LineString\",\"coordinates\":[")
            for (i in line.indices) {
                val p = line[i]
                if (i > 0) sb.append(',')
                sb.append('[').append(p.longitude).append(',').append(p.latitude).append(']')
            }
            sb.append("]}}")
        }
        sb.append("]}")
        return sb.toString()
    }

    /** A minimal MapLibre style with a matte dark background and no external tiles. */
    val DARK_STYLE: String = """
        {"version":8,"name":"mappilot-dark","sources":{},"layers":[
        {"id":"bg","type":"background","paint":{"background-color":"#0A0A0A"}}]}
    """.trimIndent()
}
