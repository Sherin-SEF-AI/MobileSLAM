package com.mappilot.search

import com.mappilot.core.database.Haversine
import com.mappilot.core.database.MapPilotDatabase
import com.mappilot.core.database.SpatialQueries
import com.mappilot.core.database.dao.AssetDao
import com.mappilot.core.database.dao.EmbeddingDao
import com.mappilot.core.database.toDomain
import com.mappilot.core.database.vector.VectorMath
import com.mappilot.core.model.Asset
import com.mappilot.core.model.AssetClass
import javax.inject.Inject
import javax.inject.Singleton

/** An asset with its great-circle distance (m) from the query point. */
data class AssetHit(val asset: Asset, val distanceM: Double)

/** An asset with its semantic similarity score (cosine, -1..1). */
data class AssetMatch(val asset: Asset, val score: Float)

/**
 * Spatial, attribute, and semantic search over the on-device asset database.
 *
 * Spatial queries prefilter with the R*Tree (when the platform provides it; an
 * indexed lat/lon bbox scan otherwise — same results), then refine by exact
 * haversine. Semantic search ranks asset embeddings by cosine similarity to a
 * query vector (the text→vector embedder is a separate seam: an on-device or
 * cloud model).
 */
@Singleton
class SearchService @Inject constructor(
    private val assetDao: AssetDao,
    private val embeddingDao: EmbeddingDao,
) {
    private val useRtree: Boolean get() = MapPilotDatabase.rtreeAvailable

    /** Assets within [radiusM] of (lat,lon), optionally a single class, nearest first. */
    suspend fun assetsWithinRadius(
        lat: Double,
        lon: Double,
        radiusM: Double,
        assetClass: AssetClass? = null,
    ): List<AssetHit> {
        val (minLat, maxLat, minLon, maxLon) = SpatialQueries.boundingBox(lat, lon, radiusM).let {
            Box(it[0], it[1], it[2], it[3])
        }
        val candidates = assetDao.spatial(
            SpatialQueries.assetsInBox(minLat, maxLat, minLon, maxLon, assetClass?.name, useRtree),
        )
        return candidates.asSequence()
            .map { it.toDomain() }
            .map { AssetHit(it, Haversine.distanceM(lat, lon, it.geo.latitude, it.geo.longitude)) }
            .filter { it.distanceM <= radiusM } // refine: bbox is a superset of the circle
            .sortedBy { it.distanceM }
            .toList()
    }

    /** All assets within a lat/lon bounding box. */
    suspend fun assetsInBbox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<Asset> =
        assetDao.spatial(SpatialQueries.assetsInBox(minLat, maxLat, minLon, maxLon, null, useRtree))
            .map { it.toDomain() }

    suspend fun assetsByClass(assetClass: AssetClass): List<Asset> =
        assetDao.byClass(assetClass.name).map { it.toDomain() }

    /**
     * Semantic search: rank stored asset embeddings by cosine similarity to
     * [queryVector] and return the top [k] assets. The query vector is produced by
     * an embedder (CLIP-like) elsewhere; this ranks against it. Returns empty when
     * no embeddings exist (no fabricated matches).
     */
    suspend fun semanticSearch(queryVector: FloatArray, k: Int = 20): List<AssetMatch> {
        val embeddings = embeddingDao.all()
        if (embeddings.isEmpty()) return emptyList()
        val scored = VectorMath.topK(
            queryVector,
            embeddings.map { it.id to VectorMath.decode(it.vector) },
            k,
        )
        val byEmbeddingId = assetDao.byEmbeddingIds(scored.map { it.id })
            .associateBy { it.embeddingId }
        return scored.mapNotNull { s ->
            byEmbeddingId[s.id]?.let { AssetMatch(it.toDomain(), s.score) }
        }
    }

    /**
     * Visual-similarity search: rank assets by embedding cosine similarity to the
     * asset [assetId] (excluding itself). Empty when the seed asset has no
     * embedding (e.g. captured without an embedder) — no fabricated matches.
     */
    suspend fun similarToAsset(assetId: Long, k: Int = 20): List<AssetMatch> {
        val embId = assetDao.byId(assetId)?.embeddingId ?: return emptyList()
        val seed = embeddingDao.byIds(listOf(embId)).firstOrNull() ?: return emptyList()
        return semanticSearch(VectorMath.decode(seed.vector), k + 1)
            .filter { it.asset.id != assetId }
            .take(k)
    }

    /** Number of stored embeddings — lets the UI know if similarity search is usable. */
    suspend fun embeddingCount(): Int = embeddingDao.all().size

    private data class Box(val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double)
}
