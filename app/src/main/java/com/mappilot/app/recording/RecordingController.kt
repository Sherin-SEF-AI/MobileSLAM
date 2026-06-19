package com.mappilot.app.recording

import android.content.Context
import com.mappilot.app.capture.SensorHub
import com.mappilot.app.perception.PerceptionController
import com.mappilot.app.slam.SlamController
import com.mappilot.core.common.bus.EventBus
import com.mappilot.core.common.config.ConfigProvider
import com.mappilot.core.common.log.Log
import com.mappilot.core.common.log.Streams
import com.mappilot.core.common.time.TimeSource
import com.mappilot.core.model.MapPilotEvent
import com.mappilot.core.model.RecordingState
import com.mappilot.core.timesync.SyncEngine
import com.mappilot.recording.mcap.McapRecoverer
import dagger.hilt.android.qualifiers.ApplicationContext
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
    private val eventBus: EventBus,
    private val timeSource: TimeSource,
    private val syncEngine: SyncEngine,
    private val configProvider: ConfigProvider,
) {
    private val _state = MutableStateFlow(RecordingState.IDLE)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private var session: RecordingSession? = null

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
            // Best-effort SLAM + georeferencing. If the camera is busy, the engine
            // reports UNAVAILABLE loudly (no fabricated poses); recording continues.
            slamController.start()
            // On-device perception runs at reduced cadence, decoupled from record.
            perceptionController.start()
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
        return result
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
