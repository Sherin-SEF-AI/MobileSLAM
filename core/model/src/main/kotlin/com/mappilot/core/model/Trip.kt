package com.mappilot.core.model

/** Where an artifact came from. Cloud results are always tagged so the UI can mark them. */
enum class Provenance { ON_DEVICE, CLOUD_REFINED, CLOUD_RECONSTRUCTION, MANUAL }

/** Lifecycle of a capture session. */
enum class TripStatus { RECORDING, RECORDED, UPLOADING, PROCESSING, READY, FAILED }

/** A capture session. Raw streams live in [mcapPath]/[mp4Path]; this is the queryable header. */
data class Trip(
    val id: Long,
    val startedNs: Long,
    val endedNs: Long?,
    val distanceM: Double,
    val areaM2: Double,
    val slamScore: Float,
    val gnssScore: Float,
    val mcapPath: String,
    val mp4Path: String?,
    val status: TripStatus,
    val provenance: Provenance,
)

/** Categorised device/runtime event recorded to the `/events` topic and DB. */
enum class DeviceEventType {
    SYNC_WARNING,
    THERMAL_STATE,
    GNSS_LOSS,
    TRACKING_LOSS,
    RECORDING_STATE,
    STORAGE_PRESSURE,
}

data class DeviceEvent(
    val timestampNs: Long,
    val type: DeviceEventType,
    val payload: String,
)

/** A cloud upload/processing job for one artifact of a trip. */
data class UploadJob(
    val id: Long,
    val tripId: Long,
    val artifact: String,
    val remoteId: String?,
    val state: String, // CloudState.name
    val bytesSent: Long,
    val totalBytes: Long,
    val provenance: Provenance,
)
