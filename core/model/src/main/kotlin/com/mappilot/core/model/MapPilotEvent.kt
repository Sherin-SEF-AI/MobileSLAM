package com.mappilot.core.model

/** Device thermal status, mirrors [android.os.PowerManager] thermal levels. */
enum class ThermalState { NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN }

/** Recording session state, owned by the foreground service. */
enum class RecordingState { IDLE, STARTING, RECORDING, STOPPING, ERROR }

/** Categories of sync-health problems raised by the SyncEngine. */
enum class SyncWarningKind { DRIFT, HIGH_LATENCY, GAP, OUT_OF_ORDER, TIMEBASE_UNKNOWN }

data class SyncWarning(
    val timestampNs: Long,
    val stream: String,
    val kind: SyncWarningKind,
    val detail: String,
)

/**
 * One VIO-frame point paired with its absolute WGS84 position, produced by the
 * ARCore Geospatial (VPS) conversion. A small set of these per keyframe is enough
 * to solve the VIO->ENU similarity transform without GPS-vs-VIO alignment.
 */
data class GeoCorrespondence(val vio: Vector3, val geo: GeoPoint)

/**
 * The single typed event carried over the in-process bus (a SharedFlow in
 * :core:common). Hot-path producers (camera, IMU, GNSS, SLAM, perception) emit
 * these; recording, fusion, and UI subscribe. No direct cross-module calls on
 * the hot path.
 */
sealed interface MapPilotEvent {
    val timestampNs: Long

    data class FrameCaptured(override val timestampNs: Long, val frame: FrameMeta) : MapPilotEvent
    data class ImuBatch(override val timestampNs: Long, val samples: List<ImuSample>) : MapPilotEvent
    data class RotationUpdate(override val timestampNs: Long, val sample: RotationSample) : MapPilotEvent
    data class GnssFixReceived(override val timestampNs: Long, val epoch: GnssEpoch) : MapPilotEvent
    data class PoseUpdate(override val timestampNs: Long, val pose: Pose) : MapPilotEvent
    data class EnuPoseUpdate(override val timestampNs: Long, val pose: EnuPose) : MapPilotEvent
    data class KeyframeSelected(override val timestampNs: Long, val keyframe: Keyframe) : MapPilotEvent
    data class LandmarksUpdated(override val timestampNs: Long, val landmarks: List<Landmark>) : MapPilotEvent
    data class DetectionBatch(override val timestampNs: Long, val detections: List<Detection>) : MapPilotEvent
    data class AssetsExtracted(override val timestampNs: Long, val assets: List<Asset>) : MapPilotEvent
    data class SyncWarningRaised(override val timestampNs: Long, val warning: SyncWarning) : MapPilotEvent
    data class ThermalChanged(override val timestampNs: Long, val state: ThermalState) : MapPilotEvent
    data class RecordingStateChanged(override val timestampNs: Long, val state: RecordingState) : MapPilotEvent
    data class DeviceEventRaised(override val timestampNs: Long, val event: DeviceEvent) : MapPilotEvent

    /**
     * Earth-anchored (VPS) correspondences for one keyframe: VIO points and their
     * WGS84 positions from ARCore Geospatial, plus the reported accuracies. The
     * fusion layer turns these into a drift-corrected VIO->ENU transform.
     */
    data class GeospatialUpdate(
        override val timestampNs: Long,
        val correspondences: List<GeoCorrespondence>,
        val horizontalAccuracyM: Float,
        val headingAccuracyDeg: Float,
    ) : MapPilotEvent
}
