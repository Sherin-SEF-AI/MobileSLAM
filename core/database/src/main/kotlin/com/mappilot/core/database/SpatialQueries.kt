package com.mappilot.core.database

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlin.math.cos
import kotlin.math.max

/**
 * Builds the R*Tree-accelerated spatial queries. The query prefilters candidates
 * via the rtree virtual table (an index range scan), then joins the base table.
 * A radius is converted to a lat/lon bounding box; callers refine with a precise
 * haversine pass ([Haversine]) since the box is a superset.
 */
object SpatialQueries {

    /** Bounding box (minLat,maxLat,minLon,maxLon) around a centre + radius (m). */
    fun boundingBox(lat: Double, lon: Double, radiusM: Double): DoubleArray {
        val dLat = radiusM / 111_320.0
        val dLon = radiusM / (111_320.0 * max(0.000001, cos(Math.toRadians(lat))))
        return doubleArrayOf(lat - dLat, lat + dLat, lon - dLon, lon + dLon)
    }

    /** The asset bbox SQL (args order: minLon,maxLon,minLat,maxLat[,class]). Exposed for testing. */
    fun assetBoxSql(useRtree: Boolean, withClass: Boolean): String {
        val base = if (useRtree) {
            "SELECT a.* FROM assets a JOIN asset_rtree r ON a.id = r.id " +
                "WHERE r.maxLon >= ? AND r.minLon <= ? AND r.maxLat >= ? AND r.minLat <= ?"
        } else {
            "SELECT * FROM assets WHERE lon >= ? AND lon <= ? AND lat >= ? AND lat <= ?"
        }
        val classClause = if (withClass) (if (useRtree) " AND a.assetClass = ?" else " AND assetClass = ?") else ""
        return base + classClause
    }

    fun assetsInBox(
        minLat: Double, maxLat: Double, minLon: Double, maxLon: Double,
        assetClass: String? = null,
        useRtree: Boolean = true,
    ): SupportSQLiteQuery {
        val sql = assetBoxSql(useRtree, assetClass != null)
        val args = mutableListOf<Any>(minLon, maxLon, minLat, maxLat)
        if (assetClass != null) args.add(assetClass)
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }

    fun fixesInBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, useRtree: Boolean = true): SupportSQLiteQuery =
        SimpleSQLiteQuery(
            if (useRtree) {
                "SELECT f.* FROM gnss_fixes f JOIN gnss_fix_rtree r ON f.id = r.id " +
                    "WHERE r.maxLon >= ? AND r.minLon <= ? AND r.maxLat >= ? AND r.minLat <= ?"
            } else {
                "SELECT * FROM gnss_fixes WHERE lon >= ? AND lon <= ? AND lat >= ? AND lat <= ?"
            },
            arrayOf(minLon, maxLon, minLat, maxLat),
        )

    fun landmarksInBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, useRtree: Boolean = true): SupportSQLiteQuery =
        SimpleSQLiteQuery(
            if (useRtree) {
                "SELECT l.* FROM landmarks l JOIN landmark_rtree r ON l.id = r.id " +
                    "WHERE r.maxLon >= ? AND r.minLon <= ? AND r.maxLat >= ? AND r.minLat <= ?"
            } else {
                "SELECT * FROM landmarks WHERE lon >= ? AND lon <= ? AND lat >= ? AND lat <= ?"
            },
            arrayOf(minLon, maxLon, minLat, maxLat),
        )
}

/** Great-circle distance for precise radius refinement after the rtree prefilter. */
object Haversine {
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(a))
    }
}
