package com.mappilot.app.capture

import com.mappilot.core.model.Constellation
import com.mappilot.core.model.GnssFix
import com.mappilot.core.model.StreamHealth
import com.mappilot.core.model.SyncWarning
import com.mappilot.core.model.TimestampSource
import com.mappilot.core.model.TrackingState

/** Immutable HUD snapshot rendered by the Capture screen. All values measured. */
data class CaptureHudState(
    val running: Boolean = false,
    val camera: CameraHud = CameraHud(),
    val imu: ImuHud = ImuHud(),
    val gnss: GnssHud = GnssHud(),
    val slam: SlamHud = SlamHud(),
    val perception: PerceptionHud = PerceptionHud(),
    val streams: List<StreamHealth> = emptyList(),
    val warnings: List<SyncWarning> = emptyList(),
)

data class PerceptionHud(
    val active: Boolean = false,
    val delegate: String = "none",
    val unavailableReason: String? = null,
    val framesProcessed: Long = 0,
    val framesDropped: Long = 0,
    val lastDetections: Int = 0,
    val assetCount: Int = 0,
)

data class SlamHud(
    val available: Boolean = false,
    val trackingState: TrackingState = TrackingState.STOPPED,
    val quality: Float = -1f,
    val keyframes: Int = 0,
    val landmarks: Int = 0,
    val trajectoryLengthM: Double = 0.0,
    val hasArcoreIntrinsics: Boolean = false,
    val depthAvailable: Boolean = false,
    val unavailableReason: String? = null,
    // Georeferencing (Umeyama VIO->ENU)
    val georeferenced: Boolean = false,
    val correspondences: Int = 0,
    val alignmentRmsM: Double = Double.NaN,
    val alignmentScale: Double = Double.NaN,
    val georefSource: String = "none", // "vps" | "gps" | "none"
    // ARCore Geospatial / VPS
    val vpsSupported: Boolean = false,
    val vpsAvailable: Boolean? = null,
    val earthTracking: Boolean = false,
    val geoLat: Double = Double.NaN,
    val geoLon: Double = Double.NaN,
    val geoHeadingDeg: Double = Double.NaN,
    val geoHAccuracyM: Double = -1.0,
)

data class CameraHud(
    val available: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Double = 0.0,
    val timestampSource: TimestampSource = TimestampSource.UNKNOWN,
    val exposureMs: Double? = null,
    val iso: Int? = null,
    val hasIntrinsics: Boolean = false,
)

data class ImuHud(
    val accelHz: Double = 0.0,
    val gyroHz: Double = 0.0,
    val magHz: Double = 0.0,
    val rotationHz: Double = 0.0,
    val droppedTotal: Long = 0,
    val directChannelSupported: Boolean = false,
)

data class GnssHud(
    val hasFix: Boolean = false,
    val fix: GnssFix? = null,
    val satellitesUsed: Int = 0,
    val satellitesVisible: Int = 0,
    val meanCn0: Float = 0f,
    val perConstellation: Map<Constellation, Int> = emptyMap(),
)
