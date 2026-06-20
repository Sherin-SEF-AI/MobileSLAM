package com.mappilot.app.sessions

import com.mappilot.core.model.GeoPoint

/** Extracts [lon,lat(,alt)] coordinate pairs from a GeoJSON LineString feature. */
object GeoJsonCoords {
    private val COORD = Regex("""\[\s*(-?\d+\.?\d*)\s*,\s*(-?\d+\.?\d*)(?:\s*,\s*(-?\d+\.?\d*))?\s*]""")

    fun lineStringPoints(geoJson: String): List<GeoPoint> {
        val idx = geoJson.indexOf("LineString")
        if (idx < 0) return emptyList()
        val coordsSection = geoJson.substring(idx)
        return COORD.findAll(coordsSection).map { m ->
            val lon = m.groupValues[1].toDouble()
            val lat = m.groupValues[2].toDouble()
            val alt = m.groupValues[3].toDoubleOrNull() ?: 0.0
            GeoPoint(lat, lon, alt)
        }.toList()
    }
}
