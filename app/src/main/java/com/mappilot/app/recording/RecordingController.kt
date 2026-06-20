package com.mappilot.app.recording

import android.content.Context
import com.mappilot.app.capture.SensorHub
import com.mappilot.app.hardening.BatteryMonitor
import com.mappilot.app.hardening.DegradationController
import com.mappilot.app.hardening.StorageManager
import com.mappilot.app.hardening.ThermalManager
import com.mappilot.app.perception.PerceptionController
import com.mappilot.app.slam.SlamController
import com.mappilot.core.common.bus.EventBus
import com.mappilot.core.common.hardening.StorageAction
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import com.mappilot.core.common.config.ConfigProvider
import com.mappilot.core.common.log.Log
import com.mappilot.core.common.log.Streams
import com.mappilot.core.common.time.TimeSource
import com.mappilot.core.database.MapPilotRepository
import com.mappilot.core.model.MapPilotEvent
import com.mappilot.core.model.Provenance
import com.mappilot.core.model.RecordingState
import com.mappilot.core.model.Trip
import com.mappilot.core.model.TripStatus
import com.mappilot.core.timesync.SyncEngine
import com.mappilot.recording.mcap.McapRecoverer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide owner of the recording lifecycle. The [RecordingService] drives it
 * so recording survives the Capture screen leaving the foreground. Also runs
 * crash recovery over previously-written trips on startup.
 */
@Singleton
class RecordingController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sensorHub: SensorHub,
    private val slamController: SlamController,
    private val perceptionController: PerceptionController,
    private val repository: MapPilotRepository,
    private val thermalManager: ThermalManager,
    private val storageManager: StorageManager,
    private val batteryMonitor: BatteryMonitor,
    private val degradationController: DegradationController,
    private val eventBus: EventBus,
    private val timeSource: TimeSource,
    private val syncEngine: SyncEngine,
    private val configProvider: ConfigProvider,
) {
    private val _state = MutableStateFlow(RecordingState.IDLE)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: RecordingSession? = null

    // Session-scoped accumulation of DB-bound derived data that only the bus
    // carries (keyframes, GNSS epoch summaries, device events). Appended on the
    // collection coroutine, snapshotted at stop; guarded by [collectLock].
    private val collectLock = Any()
    private val collectedKeyframes = ArrayList<com.mappilot.core.model.Keyframe>()
    private val collectedEpochs = ArrayList<com.mappilot.core.model.GnssEpoch>()
    private val collectedEvents = ArrayList<com.mappilot.core.model.DeviceEvent>()
    private var collectJob: Job? = null

    private val tripsRoot: File
        get() = File(context.getExternalFilesDir(null), "trips").also { it.mkdirs() }

    @Synchronized
    fun start() {
        if (_state.value == RecordingState.RECORDING || _state.value == RecordingState.STARTING) return
        emit(RecordingState.STARTING)
        try {
            val tripId = timeSource.wallClockMillis()
            val dir = File(tripsRoot, "trip_$tripId")
            val s = RecordingSession(dir, tripId, sensorHub, eventBus, timeSource, syncEngine, configProvider)
            s.start()
            session = s
            startCollecting()
            // Best-effort SLAM + georeferencing. If the camera is busy, the engine
            // reports UNAVAILABLE loudly (no fabricated poses); recording continues.
            slamController.start()
            // On-device perception runs at reduced cadence, decoupled from record.
            perceptionController.start()
            // Hardening: thermal/storage degradation + battery instrumentation.
            thermalManager.start()
            storageManager.start()
            batteryMonitor.start()
            observeStoragePressure()
            emit(RecordingState.RECORDING)
        } catch (e: Exception) {
            Log.e(Streams.RECORDING, e, "Failed to start recording")
            emit(RecordingState.ERROR)
        }
    }

    @Synchronized
    fun stop(): RecordingResult? {
        val s = session ?: return null
        emit(RecordingState.STOPPING)
        // Capture derived results before tearing down the controllers.
        val (assets, assetEmbeddings) = perceptionController.currentAssetsWithEmbeddings()
        val landmarks = slamController.currentLandmarks()
        val trajectoryGeoJson = slamController.trajectory.toGeoJson()
        val trajectoryLengthM = slamController.trajectory.lengthM()
        val slamScore = slamController.slamState.value.quality.coerceAtLeast(0f)
        val gnssScore = if (slamController.fusionState.value.aligned) 1f else 0f
        val sessionData = stopCollecting()

        thermalManager.stop()
        storageManager.stop()
        batteryMonitor.stop()
        perceptionController.stop()
        slamController.stop()
        val result = try {
            s.stop()
        } catch (e: Exception) {
            Log.e(Streams.RECORDING, e, "Error stopping recording")
            null
        }
        session = null
        emit(RecordingState.IDLE)

        if (result != null) {
            // trajectory.geojson sidecar for the Session Detail map.
            runCatching { File(result.tripDir, "trajectory.geojson").writeText(trajectoryGeoJson) }
            persistTrip(result, trajectoryLengthM, slamScore, gnssScore, assets, assetEmbeddings, landmarks, sessionData)
        }
        return result
    }

    /** Collected keyframes / GNSS epoch summaries / device events for one session. */
    private data class SessionData(
        val keyframes: List<com.mappilot.core.model.Keyframe>,
        val epochs: List<com.mappilot.core.model.GnssEpoch>,
        val events: List<com.mappilot.core.model.DeviceEvent>,
    )

    private fun startCollecting() {
        synchronized(collectLock) {
            collectedKeyframes.clear(); collectedEpochs.clear(); collectedEvents.clear()
        }
        collectJob = eventBus.events
            .onEach { event ->
                when (event) {
                    is MapPilotEvent.KeyframeSelected ->
                        synchronized(collectLock) { collectedKeyframes.add(event.keyframe) }
                    is MapPilotEvent.GnssFixReceived ->
                        synchronized(collectLock) { collectedEpochs.add(event.epoch) }
                    is MapPilotEvent.DeviceEventRaised ->
                        synchronized(collectLock) { collectedEvents.add(event.event) }
                    else -> Unit
                }
            }
            .launchIn(ioScope)
    }

    private fun stopCollecting(): SessionData {
        collectJob?.cancel()
        collectJob = null
        return synchronized(collectLock) {
            SessionData(
                keyframes = collectedKeyframes.toList(),
                epochs = collectedEpochs.toList(),
                events = collectedEvents.toList(),
            )
        }
    }

    /**
     * Stop recording only when the disk is critically full — continuing would
     * corrupt the active MCAP. This is the one sanctioned reason to end a session
     * early; thermal/perception pressure never stops recording.
     */
    private fun observeStoragePressure() {
        degradationController.status
            .onEach { if (it.storageAction == StorageAction.STOP_RECORDING && session != null) {
                Log.w(Streams.RECORDING, "Storage critical — stopping recording to protect the file")
                stop()
            } }
            .launchIn(ioScope)
    }

    /** Persist the trip header + georeferenced assets/landmarks/keyframes/GNSS/events. */
    private fun persistTrip(
        result: RecordingResult,
        distanceM: Double,
        slamScore: Float,
        gnssScore: Float,
        assets: List<com.mappilot.core.model.Asset>,
        assetEmbeddings: List<FloatArray?>,
        landmarks: List<com.mappilot.core.model.Landmark>,
        sessionData: SessionData,
    ) {
        ioScope.launch {
            try {
                val tripId = repository.upsertTrip(
                    Trip(
                        id = 0,
                        startedNs = result.startedNs,
                        endedNs = result.endedNs,
                        distanceM = distanceM,
                        areaM2 = 0.0,
                        slamScore = slamScore,
                        gnssScore = gnssScore,
                        mcapPath = File(result.tripDir, result.segments.first()).absolutePath,
                        mp4Path = result.mp4Path,
                        status = TripStatus.RECORDED,
                        provenance = Provenance.ON_DEVICE,
                    ),
                )
                repository.saveAssets(tripId, assets, assetEmbeddings)
                if (landmarks.isNotEmpty()) repository.saveLandmarks(tripId, landmarks)
                repository.saveKeyframes(tripId, sessionData.keyframes)
                repository.saveGnssEpochSummaries(tripId, sessionData.epochs)
                repository.saveEvents(tripId, sessionData.events)
                Log.i(
                    Streams.RECORDING,
                    "Persisted trip $tripId: ${assets.size} assets, ${landmarks.size} landmarks, " +
                        "${sessionData.keyframes.size} keyframes, ${sessionData.epochs.size} gnss epochs, " +
                        "${sessionData.events.size} events, ${"%.1f".format(distanceM)}m",
                )
            } catch (e: Exception) {
                Log.e(Streams.RECORDING, e, "Failed to persist trip")
            }
        }
    }

    /**
     * Scan all trips for MCAP segments left unsealed by a crash and finalize them.
     * Safe to call on every launch — already-valid files are left untouched.
     */
    fun recoverInterruptedTrips(): List<McapRecoverer.Outcome> {
        val outcomes = ArrayList<McapRecoverer.Outcome>()
        tripsRoot.listFiles()?.forEach { tripDir ->
            tripDir.listFiles { f -> f.name.endsWith(".mcap") }?.forEach { mcap ->
                val outcome = McapRecoverer.recover(mcap)
                if (outcome is McapRecoverer.Outcome.Recovered) {
                    Log.i(Streams.RECORDING, "Recovered ${mcap.path}: ${outcome.messages} messages")
                }
                outcomes.add(outcome)
            }
        }
        return outcomes
    }

    private fun emit(state: RecordingState) {
        _state.value = state
        eventBus.emit(MapPilotEvent.RecordingStateChanged(timeSource.elapsedRealtimeNanos(), state))
    }
}
