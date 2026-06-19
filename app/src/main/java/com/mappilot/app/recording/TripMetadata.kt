package com.mappilot.app.recording

import kotlinx.serialization.Serializable

/**
 * `trip_metadata.json` — the sidecar describing a capture session: device,
 * calibration, sync health, and per-stream counts. Every value is recorded from
 * the actual session, never assumed.
 */
@Serializable
data class TripMetadata(
    val tripId: Long,
    val startedNs: Long,
    val endedNs: Long,
    val device: DeviceInfo,
    val calibration: CalibrationInfo,
    val syncHealth: List<StreamHealthInfo>,
    val segments: List<String>,
    val mp4Path: String?,
    val frameCount: Int,
    val schemaVersion: Int = 1,
)

@Serializable
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val osVersion: String,
    val appVersion: String,
)

@Serializable
data class CalibrationInfo(
    val hasCameraIntrinsics: Boolean,
    val fx: Double? = null,
    val fy: Double? = null,
    val cx: Double? = null,
    val cy: Double? = null,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val cameraTimestampSource: String,
)

@Serializable
data class StreamHealthInfo(
    val streamId: String,
    val source: String,
    val appliedOffsetNs: Long,
    val rateHz: Double,
    val samplesReceived: Long,
    val samplesDropped: Long,
    val outOfOrderCount: Long,
    val gapCount: Long,
)
