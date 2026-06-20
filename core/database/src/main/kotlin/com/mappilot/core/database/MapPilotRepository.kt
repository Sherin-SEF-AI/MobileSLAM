package com.mappilot.core.database

import com.mappilot.core.database.entity.EmbeddingEntity
import com.mappilot.core.database.vector.VectorMath
import com.mappilot.core.model.Asset
import com.mappilot.core.model.Trip
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Write-side facade over the DAOs: maps domain models to entities and persists
 * them, attaching optional embeddings for semantic search. Read/query paths live
 * in `:search`.
 */
@Singleton
class MapPilotRepository @Inject constructor(
    private val db: MapPilotDatabase,
) {
    suspend fun upsertTrip(trip: Trip): Long {
        val entity = trip.toEntity()
        return if (entity.id == 0L) db.tripDao().insert(entity)
        else { db.tripDao().update(entity); entity.id }
    }

    suspend fun trips(limit: Int, offset: Int): List<Trip> =
        db.tripDao().page(limit, offset).map { it.toDomain() }

    /** Persist an extracted asset and (optionally) its embedding; returns asset id. */
    suspend fun saveAsset(tripId: Long, asset: Asset, embedding: FloatArray?): Long {
        val embeddingId = embedding?.let {
            db.embeddingDao().insert(EmbeddingEntity(dim = it.size, vector = VectorMath.encode(it)))
        }
        return db.assetDao().insert(asset.toEntity(tripId, embeddingId))
    }

    suspend fun assetsForTrip(tripId: Long): List<Asset> =
        db.assetDao().byTrip(tripId).map { it.toDomain() }

    suspend fun assetCount(): Int = db.assetDao().count()
}
