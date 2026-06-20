package com.mappilot.analytics

import com.mappilot.core.model.GeoPoint
import kotlin.math.abs
import kotlin.math.cos

/**
 * Planimetric geometry over geographic points: convex hull and polygon area via a
 * local equirectangular projection (metres). Pure and unit-tested — coverage area
 * is computed from real trajectory points, never assumed.
 */
object GeoGeometry {

    private data class P(val x: Double, val y: Double)

    /** Project lat/lon to local metres relative to the centroid (small-area accurate). */
    private fun project(points: List<GeoPoint>): List<P> {
        if (points.isEmpty()) return emptyList()
        val lat0 = points.map { it.latitude }.average()
        val lon0 = points.map { it.longitude }.average()
        val mPerDegLat = 110_574.0
        val mPerDegLon = 111_320.0 * cos(Math.toRadians(lat0))
        return points.map { P((it.longitude - lon0) * mPerDegLon, (it.latitude - lat0) * mPerDegLat) }
    }

    /** Convex-hull area in m² of the trajectory's footprint (0 for < 3 points). */
    fun coverageAreaM2(points: List<GeoPoint>): Double {
        val projected = project(points)
        if (projected.size < 3) return 0.0
        val hull = convexHull(projected)
        if (hull.size < 3) return 0.0
        // Shoelace.
        var sum = 0.0
        for (i in hull.indices) {
            val a = hull[i]; val b = hull[(i + 1) % hull.size]
            sum += a.x * b.y - b.x * a.y
        }
        return abs(sum) / 2.0
    }

    /** Andrew's monotone chain convex hull. */
    private fun convexHull(pts: List<P>): List<P> {
        val sorted = pts.distinct().sortedWith(compareBy({ it.x }, { it.y }))
        if (sorted.size < 3) return sorted
        fun cross(o: P, a: P, b: P) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        val lower = ArrayList<P>()
        for (p in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0) lower.removeAt(lower.size - 1)
            lower.add(p)
        }
        val upper = ArrayList<P>()
        for (p in sorted.reversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0) upper.removeAt(upper.size - 1)
            upper.add(p)
        }
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        return lower + upper
    }
}
