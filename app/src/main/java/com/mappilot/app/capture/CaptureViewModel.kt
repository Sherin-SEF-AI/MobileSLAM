package com.mappilot.app.capture

import android.content.Context
import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mappilot.app.recording.RecordingController
import com.mappilot.app.service.RecordingService
import com.mappilot.core.common.bus.EventBus
import com.mappilot.core.model.RecordingState
import dagger.hilt.android.qualifiers.ApplicationContext
import com.mappilot.core.model.Constellation
import com.mappilot.core.model.GnssEpoch
import com.mappilot.core.model.MapPilotEvent
import com.mappilot.core.model.StreamIds
import com.mappilot.core.timesync.SyncEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Backs the Capture screen HUD. Subscribes to the [SyncEngine] health (rates,
 * drift, latency, drops per stream) and the [EventBus] (latest frame + GNSS
 * epoch), and projects them into an immutable [CaptureHudState]. No value is
 * synthesized — when a stream has produced nothing, its fields read zero/empty,
 * not a plausible placeholder.
 */
@HiltViewModel
class CaptureViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sensorHub: SensorHub,
    private val syncEngine: SyncEngine,
    private val eventBus: EventBus,
    private val slamController: com.mappilot.app.slam.SlamController,
    private val perceptionController: com.mappilot.app.perception.PerceptionController,
    private val captureHealthMonitor: CaptureHealthMonitor,
    recordingController: RecordingController,
) : ViewModel() {

    /** Recording state, driven by the foreground service / controller. */
    val recordingState: StateFlow<RecordingState> = recordingController.state

    private val latestFrame = MutableStateFlow<MapPilotEvent.FrameCaptured?>(null)
    private val latestEpoch = MutableStateFlow<GnssEpoch?>(null)

    init {
        eventBus.events
            .onEach { event ->
                when (event) {
                    is MapPilotEvent.FrameCaptured -> latestFrame.value = event
                    is MapPilotEvent.GnssFixReceived -> latestEpoch.value = event.epoch
                    else -> Unit
                }
            }
            .launchIn(viewModelScope)
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    val hud: StateFlow<CaptureHudState> =
        combine(
            syncEngine.health,
            latestFrame,
            latestEpoch,
            slamController.slamState,
            slamController.fusionState,
        ) { health, frame, epoch, slam, fusion ->
            val cam = sensorHub.camera
            val camHealth = health.streams[StreamIds.CAMERA]
            val frameMeta = frame?.frame
            val gnssHud = epoch?.let { e ->
                GnssHud(
                    hasFix = e.fix != null,
                    fix = e.fix,
                    satellitesUsed = e.satellitesUsed,
                    satellitesVisible = e.satellitesVisible,
                    meanCn0 = e.meanCn0,
                    perConstellation = e.satellites.groupingBy { it.constellation }.eachCount(),
                )
            } ?: GnssHud()

            CaptureHudState(
                running = sensorHub.isRunning,
                camera = CameraHud(
                    available = cam.isRunning,
                    width = cam.resolution.width,
                    height = cam.resolution.height,
                    fps = camHealth?.rateHz ?: 0.0,
                    timestampSource = cam.timestampSource,
                    exposureMs = frameMeta?.let { it.exposureNs / 1_000_000.0 },
                    iso = frameMeta?.iso,
                    hasIntrinsics = frameMeta?.intrinsics != null,
                ),
                imu = ImuHud(
                    accelHz = health.streams[StreamIds.IMU_ACCEL]?.rateHz ?: 0.0,
                    gyroHz = health.streams[StreamIds.IMU_GYRO]?.rateHz ?: 0.0,
                    magHz = health.streams[StreamIds.IMU_MAG]?.rateHz ?: 0.0,
                    rotationHz = health.streams[StreamIds.IMU_ROTATION]?.rateHz ?: 0.0,
                    droppedTotal = health.streams.values.sumOf { it.samplesDropped },
                    directChannelSupported = directChannelSupported,
                ),
                gnss = gnssHud,
                slam = SlamHud(
                    available = slam.available,
                    trackingState = slam.trackingState,
                    quality = slam.quality,
                    keyframes = slam.keyframeCount,
                    landmarks = slam.landmarkCount,
                    trajectoryLengthM = slam.trajectoryLengthM,
                    hasArcoreIntrinsics = slam.cameraIntrinsics != null,
                    depthAvailable = slam.depthAvailable,
                    unavailableReason = slam.unavailableReason,
                    georeferenced = fusion.aligned,
                    correspondences = fusion.correspondences,
                    alignmentRmsM = fusion.rmsErrorM,
                    alignmentScale = fusion.scale,
                    georefSource = if (fusion.vps) "vps" else if (fusion.aligned) "gps" else "none",
                    vpsSupported = slam.geospatial.supported,
                    vpsAvailable = slam.geospatial.vpsAvailable,
                    earthTracking = slam.geospatial.earthTracking,
                    geoLat = slam.geospatial.latitude,
                    geoLon = slam.geospatial.longitude,
                    geoHeadingDeg = slam.geospatial.headingDeg,
                    geoHAccuracyM = slam.geospatial.horizontalAccuracyM,
                ),
                streams = health.streams.values.sortedBy { it.streamId },
                warnings = health.warnings.takeLast(MAX_WARNINGS).asReversed(),
            )
        }.combine(perceptionController.state) { base, perc ->
            base.copy(
                perception = PerceptionHud(
                    active = perc.active,
                    delegate = perc.delegate,
                    unavailableReason = perc.unavailableReason,
                    framesProcessed = perc.framesProcessed,
                    framesDropped = perc.framesDropped,
                    lastDetections = perc.lastDetections,
                    assetCount = perc.assetCount,
                ),
            )
        }.combine(captureHealthMonitor.state) { base, ch ->
            base.copy(
                capture = CaptureHealthHud(
                    speedMps = ch.speedMps,
                    rotationDegPerS = ch.rotationDegPerS,
                    rotateInPlace = ch.rotateInPlace,
                    tooFast = ch.tooFast,
                    warning = ch.warning,
                ),
            )
        }
            // The HUD never needs to redraw faster than ~15 Hz; sampling caps
            // recomposition + state allocation independent of the 30 fps frame flow.
            .sample(HUD_SAMPLE_MS)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CaptureHudState())

    private val directChannelSupported: Boolean by lazy {
        runCatching { sensorHub.imu.supportsDirectChannel() }.getOrDefault(false)
    }

    fun onPreviewSurfaceAvailable(surface: Surface) {
        sensorHub.setPreviewSurface(surface)
        if (!sensorHub.isRunning) sensorHub.start()
    }

    fun onPreviewSurfaceDestroyed() {
        sensorHub.setPreviewSurface(null)
    }

    fun startCapture() = sensorHub.start()

    fun stopCapture() = sensorHub.stop()

    /** Toggle recording via the foreground service (FGS owns the session). */
    fun toggleRecording() {
        when (recordingState.value) {
            RecordingState.RECORDING, RecordingState.STARTING -> RecordingService.stop(context)
            else -> RecordingService.start(context)
        }
    }

    override fun onCleared() {
        sensorHub.stop()
    }

    private companion object {
        const val MAX_WARNINGS = 6
        const val HUD_SAMPLE_MS = 66L // ~15 Hz HUD refresh
    }
}
