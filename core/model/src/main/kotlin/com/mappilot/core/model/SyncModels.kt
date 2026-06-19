package com.mappilot.core.model

/**
 * Origin clock of a stream's raw timestamps, before normalization to the
 * unified `elapsedRealtimeNanos` base.
 *
 * - [REALTIME]: already in the `elapsedRealtimeNanos` base (no offset needed).
 * - [BOOTTIME]: monotonic-since-boot but a different epoch; a measured offset
 *   brings it into the unified base.
 * - [UNKNOWN]: source not advertised (e.g. camera `SENSOR_INFO_TIMESTAMP_SOURCE`
 *   == UNKNOWN); a per-device offset is measured and applied. Surfaced as a
 *   `TIMEBASE_UNKNOWN` warning rather than silently trusted.
 */
enum class TimestampSource { REALTIME, BOOTTIME, MONOTONIC, UNKNOWN }

/** Stable identifiers for every synchronized stream. Aligned with MCAP topics. */
object StreamIds {
    const val CAMERA = "camera"
    const val IMU_ACCEL = "imu/accel"
    const val IMU_GYRO = "imu/gyro"
    const val IMU_MAG = "imu/mag"
    const val IMU_LINEAR_ACCEL = "imu/linear_accel"
    const val IMU_GRAVITY = "imu/gravity"
    const val IMU_ROTATION = "imu/rotation_vector"
    const val GNSS_FIX = "gps/fix"
    const val GNSS_RAW = "gps/raw"
    const val GNSS_SAT = "gps/sat"
}

/**
 * Per-stream health, computed by the SyncEngine from real arriving timestamps.
 * Every field is measured — none is assumed.
 */
data class StreamHealth(
    val streamId: String,
    val source: TimestampSource,
    /** Measured offset added to raw timestamps to reach the unified base (ns). */
    val appliedOffsetNs: Long,
    /** Effective sample rate over the recent window (Hz); 0 until enough samples. */
    val rateHz: Double,
    /** Mean inter-stream offset vs the reference stream over the window (ns). */
    val driftNs: Long,
    /** Capture/sensor→bus latency, most recent (ns); -1 when not measured. */
    val latencyNs: Long,
    val samplesReceived: Long,
    val samplesDropped: Long,
    val outOfOrderCount: Long,
    val gapCount: Long,
    val lastTimestampNs: Long,
) {
    companion object {
        fun initial(streamId: String, source: TimestampSource) = StreamHealth(
            streamId = streamId,
            source = source,
            appliedOffsetNs = 0,
            rateHz = 0.0,
            driftNs = 0,
            latencyNs = -1,
            samplesReceived = 0,
            samplesDropped = 0,
            outOfOrderCount = 0,
            gapCount = 0,
            lastTimestampNs = 0,
        )
    }
}

/** Aggregate sync health across all registered streams. */
data class SyncHealth(
    val streams: Map<String, StreamHealth>,
    val warnings: List<SyncWarning>,
) {
    companion object {
        val EMPTY = SyncHealth(emptyMap(), emptyList())
    }
}
