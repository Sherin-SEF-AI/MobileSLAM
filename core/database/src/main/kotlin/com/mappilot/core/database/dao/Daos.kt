package com.mappilot.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import com.mappilot.core.database.entity.AssetEntity
import com.mappilot.core.database.entity.EmbeddingEntity
import com.mappilot.core.database.entity.EventEntity
import com.mappilot.core.database.entity.GnssEpochSummaryEntity
import com.mappilot.core.database.entity.GnssFixEntity
import com.mappilot.core.database.entity.KeyframeEntity
import com.mappilot.core.database.entity.LandmarkEntity
import com.mappilot.core.database.entity.TripEntity
import com.mappilot.core.database.entity.UploadJobEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Insert suspend fun insert(trip: TripEntity): Long
    @Update suspend fun update(trip: TripEntity)
    @Query("SELECT * FROM trips ORDER BY startedNs DESC") fun observeAll(): Flow<List<TripEntity>>
    @Query("SELECT * FROM trips ORDER BY startedNs DESC LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<TripEntity>
    @Query("SELECT * FROM trips WHERE id = :id") suspend fun byId(id: Long): TripEntity?
    @Query("SELECT COUNT(*) FROM trips") suspend fun count(): Int
    @Query("DELETE FROM trips WHERE id = :id") suspend fun deleteById(id: Long)
}

@Dao
interface AssetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(asset: AssetEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(assets: List<AssetEntity>): List<Long>
    @Query("SELECT * FROM assets WHERE tripId = :tripId") suspend fun byTrip(tripId: Long): List<AssetEntity>
    @Query("SELECT * FROM assets WHERE assetClass = :assetClass") suspend fun byClass(assetClass: String): List<AssetEntity>
    @Query("SELECT * FROM assets") suspend fun all(): List<AssetEntity>
    @Query("SELECT * FROM assets ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<AssetEntity>
    @Query("SELECT COUNT(*) FROM assets") suspend fun count(): Int
    @Query("SELECT * FROM assets WHERE id = :id") suspend fun byId(id: Long): AssetEntity?
    @Query("SELECT * FROM assets WHERE embeddingId IN (:ids)") suspend fun byEmbeddingIds(ids: List<Long>): List<AssetEntity>
    @Query("DELETE FROM assets WHERE tripId = :tripId") suspend fun deleteByTrip(tripId: Long)

    /** Spatial query joining the R*Tree virtual table; built by the repository. */
    @RawQuery suspend fun spatial(query: SupportSQLiteQuery): List<AssetEntity>
}

@Dao
interface GnssFixDao {
    @Insert suspend fun insert(fix: GnssFixEntity): Long
    @Insert suspend fun insertAll(fixes: List<GnssFixEntity>)
    @Query("SELECT * FROM gnss_fixes WHERE tripId = :tripId ORDER BY timestampNs") suspend fun byTrip(tripId: Long): List<GnssFixEntity>
    @Query("DELETE FROM gnss_fixes WHERE tripId = :tripId") suspend fun deleteByTrip(tripId: Long)
    @RawQuery suspend fun spatial(query: SupportSQLiteQuery): List<GnssFixEntity>
}

@Dao
interface LandmarkDao {
    @Insert suspend fun insertAll(landmarks: List<LandmarkEntity>)
    @Query("SELECT * FROM landmarks WHERE tripId = :tripId") suspend fun byTrip(tripId: Long): List<LandmarkEntity>
    @Query("SELECT COUNT(*) FROM landmarks WHERE tripId = :tripId") suspend fun countForTrip(tripId: Long): Int
    @Query("DELETE FROM landmarks WHERE tripId = :tripId") suspend fun deleteByTrip(tripId: Long)
    @RawQuery suspend fun spatial(query: SupportSQLiteQuery): List<LandmarkEntity>
}

@Dao
interface EmbeddingDao {
    @Insert suspend fun insert(embedding: EmbeddingEntity): Long
    @Query("SELECT * FROM embeddings") suspend fun all(): List<EmbeddingEntity>
    @Query("SELECT * FROM embeddings WHERE id IN (:ids)") suspend fun byIds(ids: List<Long>): List<EmbeddingEntity>
    /** Delete embeddings owned by one trip's assets (run before deleting the assets). */
    @Query("DELETE FROM embeddings WHERE id IN (SELECT embeddingId FROM assets WHERE tripId = :tripId AND embeddingId IS NOT NULL)")
    suspend fun deleteForTrip(tripId: Long)
}

@Dao
interface KeyframeDao {
    @Insert suspend fun insertAll(keyframes: List<KeyframeEntity>)
    @Query("SELECT * FROM keyframes WHERE tripId = :tripId ORDER BY timestampNs") suspend fun byTrip(tripId: Long): List<KeyframeEntity>
    @Query("DELETE FROM keyframes WHERE tripId = :tripId") suspend fun deleteByTrip(tripId: Long)
}

@Dao
interface GnssEpochSummaryDao {
    @Insert suspend fun insertAll(summaries: List<GnssEpochSummaryEntity>)
    @Query("SELECT * FROM gnss_epoch_summaries WHERE tripId = :tripId ORDER BY timestampNs") suspend fun byTrip(tripId: Long): List<GnssEpochSummaryEntity>
    @Query("DELETE FROM gnss_epoch_summaries WHERE tripId = :tripId") suspend fun deleteByTrip(tripId: Long)
}

@Dao
interface EventDao {
    @Insert suspend fun insertAll(events: List<EventEntity>)
    @Query("SELECT * FROM events WHERE tripId = :tripId ORDER BY timestampNs") suspend fun byTrip(tripId: Long): List<EventEntity>
    @Query("DELETE FROM events WHERE tripId = :tripId") suspend fun deleteByTrip(tripId: Long)
}

@Dao
interface UploadJobDao {
    @Insert suspend fun insert(job: UploadJobEntity): Long
    @Update suspend fun update(job: UploadJobEntity)
    @Query("SELECT * FROM upload_jobs ORDER BY id DESC") fun observeAll(): Flow<List<UploadJobEntity>>
    @Query("SELECT * FROM upload_jobs WHERE id = :id") suspend fun byId(id: Long): UploadJobEntity?
    @Query("SELECT * FROM upload_jobs WHERE tripId = :tripId") suspend fun byTrip(tripId: Long): List<UploadJobEntity>
    @Query("SELECT * FROM upload_jobs WHERE state = :state") suspend fun byState(state: String): List<UploadJobEntity>
    @Query("DELETE FROM upload_jobs WHERE tripId = :tripId") suspend fun deleteByTrip(tripId: Long)
}
