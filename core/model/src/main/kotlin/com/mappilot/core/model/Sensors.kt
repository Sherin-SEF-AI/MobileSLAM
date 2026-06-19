package com.mappilot.core.model

/**
 * Single camera frame descriptor. The pixel payload lives in the mp4 sidecar;
 * this carries only metadata and the link back to the video stream.
 *
 * [timestampNs] is in the unified [android.os.SystemClock.elapsedRealtimeNanos] timebase.
 */
data class FrameMeta(
    val frameId: Long,
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val exposureNs: Long,
    val iso: Int,
    val intrinsics: CameraIntrinsics?,
    val videoPtsNs: Long,
    val isKeyframe: Boolean,
)

/** Which physical sensor a tri-axis sample came from. */
enum class ImuChannel { ACCEL, GYRO, MAG, LINEAR_ACCEL, GRAVITY }

/** Tri-axis IMU sample. [accuracy] mirrors SensorManager's accuracy enum (0..3, -1 = unknown). */
data class ImuSample(
    val channel: ImuChannel,
    val timestampNs: Long,
    val x: Float,
    val y: Float,
    val z: Float,
    val accuracy: Int,
)

/** Orientation as a rotation vector / quaternion from the fused rotation sensor. */
data class RotationSample(
    val timestampNs: Long,
    val qx: Float,
    val qy: Float,
    val qz: Float,
    val qw: Float,
    val accuracy: Int,
)

/** GNSS constellations Android can report, including India's NavIC/IRNSS. */
enum class Constellation { GPS, GLONASS, GALILEO, BEIDOU, QZSS, IRNSS, SBAS, UNKNOWN }

/** Fused/raw location fix. Accuracies are 1-sigma metres; -1 when not provided. */
data class GnssFix(
    val timestampNs: Long,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val speedMps: Float,
    val bearingDeg: Float,
    val hAccuracyM: Float,
    val vAccuracyM: Float,
    val provider: String,
)

/** Per-satellite status from [android.location.GnssStatus]. */
data class GnssSatellite(
    val svid: Int,
    val constellation: Constellation,
    val cn0DbHz: Float,
    val usedInFix: Boolean,
    val azimuthDeg: Float,
    val elevationDeg: Float,
)

/** Raw pseudorange measurement from [android.location.GnssMeasurement]. */
data class GnssRawMeasurement(
    val svid: Int,
    val constellation: Constellation,
    val pseudorangeRateMps: Double,
    val accumulatedDeltaRangeM: Double,
    val cn0DbHz: Double,
    val carrierFrequencyHz: Float,
    val state: Int,
)

/** A full GNSS epoch: a fix plus the satellite view that produced it. */
data class GnssEpoch(
    val timestampNs: Long,
    val fix: GnssFix?,
    val satellites: List<GnssSatellite>,
    val rawMeasurements: List<GnssRawMeasurement>,
) {
    val satellitesUsed: Int get() = satellites.count { it.usedInFix }
    val satellitesVisible: Int get() = satellites.size
    val meanCn0: Float
        get() = satellites.takeIf { it.isNotEmpty() }?.map { it.cn0DbHz }?.average()?.toFloat() ?: 0f
}
