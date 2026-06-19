package com.mappilot.app.capture

import com.mappilot.core.model.Constellation
import com.mappilot.core.model.GnssFix
import com.mappilot.core.model.StreamHealth
import com.mappilot.core.model.SyncWarning
import com.mappilot.core.model.TimestampSource

/** Immutable HUD snapshot rendered by the Capture screen. All values measured. */
data class CaptureHudState(
    val running: Boolean = false,
    val camera: CameraHud = CameraHud(),
    val imu: ImuHud = ImuHud(),
    val gnss: GnssHud = GnssHud(),
    val streams: List<StreamHealth> = emptyList(),
    val warnings: List<SyncWarning> = emptyList(),
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
