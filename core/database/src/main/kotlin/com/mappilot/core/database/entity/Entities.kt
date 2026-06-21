package com.mappilot.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entities — the lower-volume, query-serving slice (§6.3). The full-rate raw
 * streams live in MCAP; these are the indexed, queryable header/derived records.
 * No 30 fps frames or 100 Hz IMU rows here.
 *
 * Spatial entities (fix/landmark/asset) carry plain lat/lon columns; a parallel
 * R*Tree virtual table (created in [com.mappilot.core.database.MapPilotDatabase]'s
 * callback) accelerates spatial queries and is kept in sync by triggers.
 */

@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedNs: Long,
    val endedNs: Long?,
    val distanceM: Double,
    val areaM2: Double,
    val slamScore: Float,
    val gnssScore: Float,
    val mcapPath: String,
    val mp4Path: String?,
    val status: String,      // TripStatus.name
    val provenance: String,  // Provenance.name
)

@Entity(
    tableName = "keyframes",
    indices = [Index("tripId"), Index("timestampNs")],
)
data class KeyframeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val frameId: Long,
    val timestampNs: Long,
    // pose (VIO frame)
    val px: Double, val py: Double, val pz: Double,
    val qx: Double, val qy: Double, val qz: Double, val qw: Double,
    // enu pose (nullable — present once georeferenced)
    val east: Double?, val north: Double?, val up: Double?,
    // intrinsics (nullable)
    val fx: Double?, val fy: Double?, val cx: Double?, val cy: Double?,
)

@Entity(
    tableName = "gnss_fixes",
    indices = [Index("tripId"), Index("timestampNs"), Index(value = ["lat", "lon"])],
)
data class GnssFixEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val timestampNs: Long,
    val lat: Double,
    val lon: Double,
    val alt: Double,
    val speedMps: Float,
    val bearingDeg: Float,
    val hAccuracyM: Float,
    val vAccuracyM: Float,
)

@Entity(tableName = "gnss_epoch_summaries", indices = [Index("tripId"), Index("timestampNs")])
data class GnssEpochSummaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val timestampNs: Long,
    val satsUsed: Int,
    val satsVisible: Int,
    val meanCn0: Float,
    val constellationsMask: Int,
)

@Entity(tableName = "landmarks", indices = [Index("tripId"), Index(value = ["lat", "lon"])])
data class LandmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val x: Double, val y: Double, val z: Double,
    val lat: Double?, val lon: Double?, val alt: Double?,
    val confidence: Float,
)

@Entity(tableName = "assets", indices = [Index("tripId"), Index("assetClass"), Index(value = ["lat", "lon"])])
data class AssetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val assetClass: String, // AssetClass.name
    val lat: Double,
    val lon: Double,
    val alt: Double,
    val bboxLeft: Float, val bboxTop: Float, val bboxRight: Float, val bboxBottom: Float,
    val confidence: Float,
    val sourceFrameId: Long,
    val depthM: Float?,
    val embeddingId: Long?,
    // Semantic anchoring (schema v2): majority Scene Semantics label + positional 1-sigma (m).
    val semanticLabel: String? = null,
    val positionStdM: Float? = null,
)

@Entity(tableName = "embeddings")
data class EmbeddingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dim: Int,
    val vector: ByteArray, // float32 little-endian, length dim*4
)

@Entity(tableName = "events", indices = [Index("tripId"), Index("timestampNs")])
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val timestampNs: Long,
    val type: String,
    val payload: String,
)

@Entity(tableName = "upload_jobs", indices = [Index("tripId")])
data class UploadJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val artifact: String,
    val remoteId: String?,
    val state: String,
    val bytesSent: Long,
    val totalBytes: Long,
    val provenance: String,
)
