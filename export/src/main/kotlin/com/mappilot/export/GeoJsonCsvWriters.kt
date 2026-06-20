package com.mappilot.export

import com.mappilot.core.model.Asset
import com.mappilot.core.model.GeoPoint

/**
 * GeoJSON export combining the trajectory (LineString) and assets (Points with
 * class/confidence/depth properties) into one FeatureCollection that renders in
 * QGIS / geojson.io. Empty inputs yield a valid empty collection.
 */
object GeoJsonExporter {

    fun export(trajectory: List<GeoPoint>, assets: List<Asset>): String {
        val features = ArrayList<String>()
        if (trajectory.isNotEmpty()) {
            val coords = trajectory.joinToString(",") { "[${it.longitude},${it.latitude},${alt(it)}]" }
            features.add(
                """{"type":"Feature","properties":{"kind":"trajectory","points":${trajectory.size}},""" +
                    """"geometry":{"type":"LineString","coordinates":[$coords]}}""",
            )
        }
        assets.forEach { a ->
            features.add(
                """{"type":"Feature","properties":{"kind":"asset","id":${a.id},"class":"${a.assetClass.name}",""" +
                    """"confidence":${a.confidence},"depth_m":${a.depthM ?: "null"}},""" +
                    """"geometry":{"type":"Point","coordinates":[${a.geo.longitude},${a.geo.latitude},${alt(a.geo)}]}}""",
            )
        }
        return """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""
    }

    private fun alt(g: GeoPoint): Double = if (g.altitude.isNaN()) 0.0 else g.altitude
}

/** CSV exporters for trajectory, assets, and a per-session sensor summary. */
object CsvExporter {

    fun trajectory(points: List<GeoPoint>): String = buildString {
        append("lat,lon,alt\n")
        for (p in points) append("${p.latitude},${p.longitude},${if (p.altitude.isNaN()) 0.0 else p.altitude}\n")
    }

    fun assets(assets: List<Asset>): String = buildString {
        append("id,class,lat,lon,alt,confidence,depth_m,source_frame_id\n")
        for (a in assets) {
            append("${a.id},${a.assetClass.name},${a.geo.latitude},${a.geo.longitude},${a.geo.altitude},")
            append("${a.confidence},${a.depthM ?: ""},${a.sourceFrameId}\n")
        }
    }

    fun sensorSummary(rows: List<SummaryRow>): String = buildString {
        append("stream,samples,rate_hz,dropped\n")
        for (r in rows) append("${r.stream},${r.samples},${r.rateHz},${r.dropped}\n")
    }

    data class SummaryRow(val stream: String, val samples: Long, val rateHz: Double, val dropped: Long)
}
