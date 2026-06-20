package com.mappilot.core.database

import com.mappilot.core.database.entity.EmbeddingEntity
import com.mappilot.core.database.entity.EventEntity
import com.mappilot.core.database.entity.GnssEpochSummaryEntity
import com.mappilot.core.database.entity.KeyframeEntity
import com.mappilot.core.database.entity.LandmarkEntity
import com.mappilot.core.database.entity.UploadJobEntity
import com.mappilot.core.database.vector.VectorMath
import com.mappilot.core.model.Asset
import com.mappilot.core.model.DeviceEvent
import com.mappilot.core.model.GnssEpoch
import com.mappilot.core.model.Keyframe
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
        // Drop non-finite coords: ARCore can emit NaN points during poor/early
        // tracking, and SQLite coerces a bound NaN to NULL → NOT NULL violation on
        // landmarks.x. These are invalid measurements, not real positions.
        val valid = landmarks.filter {
            it.position.x.isFinite() && it.position.y.isFinite() && it.position.z.isFinite()
        }
        if (valid.isEmpty()) return
        db.landmarkDao().insertAll(
            valid.map { l ->
                LandmarkEntity(
                    tripId = tripId,
                    x = l.position.x, y = l.position.y, z = l.position.z,
                    lat = l.geo?.latitude, lon = l.geo?.longitude, alt = l.geo?.altitude,
                    confidence = l.confidence,
                )
            },
        )
    }

    /** Persist selected keyframes (pose-graph anchors) for post-session analysis + 3D viz. */
    suspend fun saveKeyframes(tripId: Long, keyframes: List<Keyframe>) {
        // Same NaN guard as landmarks: drop keyframes whose pose has non-finite
        // position/orientation (NaN → NULL → NOT NULL violation on keyframes.px).
        val valid = keyframes.filter { k ->
            val p = k.pose.position; val q = k.pose.orientation
            p.x.isFinite() && p.y.isFinite() && p.z.isFinite() &&
                q.x.isFinite() && q.y.isFinite() && q.z.isFinite() && q.w.isFinite()
        }
        if (valid.isEmpty()) return
        db.keyframeDao().insertAll(
            valid.map { k ->
                KeyframeEntity(
                    tripId = tripId,
                    frameId = k.frameId,
                    timestampNs = k.timestampNs,
                    px = k.pose.position.x, py = k.pose.position.y, pz = k.pose.position.z,
                    qx = k.pose.orientation.x, qy = k.pose.orientation.y,
                    qz = k.pose.orientation.z, qw = k.pose.orientation.w,
                    east = k.enuPose?.enu?.east, north = k.enuPose?.enu?.north, up = k.enuPose?.enu?.up,
                    fx = k.intrinsics?.fx, fy = k.intrinsics?.fy,
                    cx = k.intrinsics?.cx, cy = k.intrinsics?.cy,
                )
            },
        )
    }

    /** Persist one summary row per GNSS epoch (quality over the session). */
    suspend fun saveGnssEpochSummaries(tripId: Long, epochs: List<GnssEpoch>) {
        if (epochs.isEmpty()) return
        db.gnssEpochSummaryDao().insertAll(
            epochs.map { e ->
                GnssEpochSummaryEntity(
                    tripId = tripId,
                    timestampNs = e.timestampNs,
                    satsUsed = e.satellitesUsed,
                    satsVisible = e.satellitesVisible,
                    meanCn0 = e.meanCn0,
                    constellationsMask = e.satellites.fold(0) { m, s -> m or (1 shl s.constellation.ordinal) },
                )
            },
        )
    }

    /** Persist device events (thermal/tracking-loss/storage/sync) for the trip log. */
    suspend fun saveEvents(tripId: Long, events: List<DeviceEvent>) {
        if (events.isEmpty()) return
        db.eventDao().insertAll(
            events.map { ev ->
                EventEntity(
                    tripId = tripId,
                    timestampNs = ev.timestampNs,
                    type = ev.type.name,
                    payload = ev.payload,
                )
            },
        )
    }

    suspend fun keyframesForTrip(tripId: Long): List<Keyframe> =
        db.keyframeDao().byTrip(tripId).map { it.toDomain() }

    /**
     * Delete a trip and every child row. Embeddings go first (they're referenced
     * by assets); the trip header goes last so a mid-way failure never leaves an
     * orphaned trip pointing at deleted children.
     */
    suspend fun deleteTrip(tripId: Long) {
        db.embeddingDao().deleteForTrip(tripId)
        db.assetDao().deleteByTrip(tripId)
        db.landmarkDao().deleteByTrip(tripId)
        db.keyframeDao().deleteByTrip(tripId)
        db.gnssEpochSummaryDao().deleteByTrip(tripId)
        db.gnssFixDao().deleteByTrip(tripId)
        db.eventDao().deleteByTrip(tripId)
        db.uploadJobDao().deleteByTrip(tripId)
        db.tripDao().deleteById(tripId)
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

    /** Upload jobs currently in [state] (e.g. PROCESSING) — used by the job poller. */
    suspend fun uploadJobsInState(state: String): List<UploadJob> =
        db.uploadJobDao().byState(state).map { it.toUploadDomain() }

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
