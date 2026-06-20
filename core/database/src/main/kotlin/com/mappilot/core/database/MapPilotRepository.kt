package com.mappilot.core.database

import com.mappilot.core.database.entity.EmbeddingEntity
import com.mappilot.core.database.entity.LandmarkEntity
import com.mappilot.core.database.entity.UploadJobEntity
import com.mappilot.core.database.vector.VectorMath
import com.mappilot.core.model.Asset
import com.mappilot.core.model.Landmark
import com.mappilot.core.model.Provenance
import com.mappilot.core.model.Trip
import com.mappilot.core.model.UploadJob
import com.mappilot.core.model.Vector3
import com.mappilot.core.database.entity.TripEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private fun Flow<List<TripEntity>>.mapEntities(): Flow<List<Trip>> = map { list -> list.map { it.toDomain() } }

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

    fun observeTrips(): kotlinx.coroutines.flow.Flow<List<Trip>> =
        db.tripDao().observeAll().mapEntities()

    /** Persist an extracted asset and (optionally) its embedding; returns asset id. */
    suspend fun saveAsset(tripId: Long, asset: Asset, embedding: FloatArray?): Long {
        val embeddingId = embedding?.let {
            db.embeddingDao().insert(EmbeddingEntity(dim = it.size, vector = VectorMath.encode(it)))
        }
        return db.assetDao().insert(asset.toEntity(tripId, embeddingId))
    }

    /**
     * Batch-persist assets in a single [AssetDao.insertAll], optionally with a
     * parallel list of embeddings (null entries → no embedding). Used at trip stop
     * where dozens–hundreds of assets land at once; one insert beats N round-trips.
     */
    suspend fun saveAssets(
        tripId: Long,
        assets: List<Asset>,
        embeddings: List<FloatArray?> = emptyList(),
    ): List<Long> {
        if (assets.isEmpty()) return emptyList()
        val entities = assets.mapIndexed { i, asset ->
            val embeddingId = embeddings.getOrNull(i)?.let {
                db.embeddingDao().insert(EmbeddingEntity(dim = it.size, vector = VectorMath.encode(it)))
            }
            asset.toEntity(tripId, embeddingId)
        }
        return db.assetDao().insertAll(entities)
    }

    suspend fun assetsForTrip(tripId: Long): List<Asset> =
        db.assetDao().byTrip(tripId).map { it.toDomain() }

    suspend fun allAssets(): List<Asset> = db.assetDao().all().map { it.toDomain() }

    /** Paged asset access for large (100 GB+) datasets — never loads everything at once. */
    suspend fun assetsPage(offset: Int, limit: Int): List<Asset> =
        db.assetDao().page(limit, offset).map { it.toDomain() }

    suspend fun assetCountTotal(): Int = db.assetDao().count()

    suspend fun assetCount(): Int = db.assetDao().count()

    suspend fun tripById(id: Long): Trip? = db.tripDao().byId(id)?.toDomain()

    suspend fun saveLandmarks(tripId: Long, landmarks: List<Landmark>) {
        db.landmarkDao().insertAll(
            landmarks.map { l ->
                LandmarkEntity(
                    tripId = tripId,
                    x = l.position.x, y = l.position.y, z = l.position.z,
                    lat = l.geo?.latitude, lon = l.geo?.longitude, alt = l.geo?.altitude,
                    confidence = l.confidence,
                )
            },
        )
    }

    // --- upload jobs ---

    suspend fun queueUpload(tripId: Long, artifact: String, totalBytes: Long): Long =
        db.uploadJobDao().insert(
            UploadJobEntity(
                tripId = tripId, artifact = artifact, remoteId = null,
                state = "QUEUED", bytesSent = 0, totalBytes = totalBytes, provenance = Provenance.CLOUD_REFINED.name,
            ),
        )

    suspend fun updateUpload(id: Long, state: String, bytesSent: Long, remoteId: String?) {
        val existing = db.uploadJobDao().byId(id) ?: return
        db.uploadJobDao().update(existing.copy(state = state, bytesSent = bytesSent, remoteId = remoteId ?: existing.remoteId))
    }

    suspend fun uploadJob(id: Long): UploadJob? = db.uploadJobDao().byId(id)?.toUploadDomain()

    fun observeUploadJobs(): Flow<List<UploadJob>> =
        db.uploadJobDao().observeAll().map { list -> list.map { it.toUploadDomain() } }

    suspend fun landmarksForTrip(tripId: Long): List<Landmark> =
        db.landmarkDao().byTrip(tripId).map { l ->
            Landmark(
                id = l.id,
                position = Vector3(l.x, l.y, l.z),
                geo = if (l.lat != null && l.lon != null) com.mappilot.core.model.GeoPoint(l.lat, l.lon, l.alt ?: 0.0) else null,
                confidence = l.confidence,
            )
        }
}
