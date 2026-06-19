package com.mappilot.geo.trajectory

import com.mappilot.core.model.GeoPoint
import kotlin.math.cos
import kotlin.math.sqrt

/** One georeferenced trajectory vertex. */
data class TrajectoryPoint(
    val timestampNs: Long,
    val geo: GeoPoint,
    val east: Double,
    val north: Double,
    val up: Double,
)

/**
 * Accumulates the georeferenced trajectory and emits GeoJSON / CSV. Pure: callers
 * feed real fused points; nothing is interpolated or invented. Distance is the
 * planimetric ENU path length.
 */
class TrajectoryBuilder {
    private val points = ArrayList<TrajectoryPoint>()

    fun add(point: TrajectoryPoint) {
        points.add(point)
    }

    val size: Int get() = points.size

    fun points(): List<TrajectoryPoint> = points.toList()

    /** Planimetric path length in metres over the ENU coordinates. */
    fun lengthM(): Double {
        var total = 0.0
        for (i in 1 until points.size) {
            val a = points[i - 1]; val b = points[i]
            val de = b.east - a.east; val dn = b.north - a.north
            total += sqrt(de * de + dn * dn)
        }
        return total
    }

    /** GeoJSON FeatureCollection: a LineString of [lon,lat,alt] plus metadata. */
    fun toGeoJson(): String {
        val coords = points.joinToString(",") { p ->
            "[${p.geo.longitude},${p.geo.latitude},${altOrZero(p.geo)}]"
        }
        return """
            |{"type":"FeatureCollection","features":[
            |{"type":"Feature","properties":{"kind":"trajectory","points":${points.size},"length_m":${"%.3f".format(lengthM())}},
            |"geometry":{"type":"LineString","coordinates":[$coords]}}
            |]}
        """.trimMargin().replace("\n", "")
    }

    /** CSV: `timestamp_ns,lat,lon,alt,east,north,up`. */
    fun toCsv(): String = buildString {
        append("timestamp_ns,lat,lon,alt,east,north,up\n")
        for (p in points) {
            append(p.timestampNs).append(',')
                .append(p.geo.latitude).append(',')
                .append(p.geo.longitude).append(',')
                .append(altOrZero(p.geo)).append(',')
                .append(p.east).append(',')
                .append(p.north).append(',')
                .append(p.up).append('\n')
        }
    }

    private fun altOrZero(g: GeoPoint): Double = if (g.altitude.isNaN()) 0.0 else g.altitude
}
