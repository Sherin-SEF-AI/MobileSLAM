package com.mappilot.cloud.client

import kotlinx.serialization.Serializable

/** App-facing lifecycle state of an upload+processing job (§3 mapping). */
enum class CloudState { QUEUED, UPLOADING, PROCESSING, READY, FAILED }

/** Cloud processing job types the backend offers. */
enum class JobType { SFM_REFINE, MVS_DENSE, GAUSSIAN_SPLAT, OPEN_VOCAB_DETECT, MAP_GEN_OPENDRIVE, MAP_GEN_LANELET2, VECTOR_TILES }

@Serializable
data class CreateUploadRequest(
    val tripId: Long,
    val artifact: String,
    val totalBytes: Long,
    val chunkSize: Int,
    val sha256: String,
)

@Serializable
data class CreateUploadResponse(val uploadId: String, val chunkSize: Int, val receivedChunks: List<Int> = emptyList())

@Serializable
data class UploadStatus(val uploadId: String, val receivedChunks: List<Int>, val totalChunks: Int, val state: String)

@Serializable
data class CompleteResponse(val artifactId: String, val state: String)

@Serializable
data class CreateJobRequest(val artifactId: String, val type: String, val tripId: Long)

@Serializable
data class CreateJobResponse(val jobId: String, val state: String)

@Serializable
data class JobStatus(
    val jobId: String,
    val state: String,
    val progress: Double = 0.0,
    val provenance: String? = null,
    val resultUrl: String? = null,
    val error: String? = null,
)
