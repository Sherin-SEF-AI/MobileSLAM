package com.mappilot.app.hardening

import com.mappilot.app.perception.PerceptionController
import com.mappilot.core.common.bus.EventBus
import com.mappilot.core.common.config.ConfigProvider
import com.mappilot.core.common.hardening.StorageAction
import com.mappilot.core.common.hardening.ThermalPolicy
import com.mappilot.core.common.log.Log
import com.mappilot.core.common.log.Streams
import com.mappilot.core.common.time.TimeSource
import com.mappilot.core.model.DeviceEvent
import com.mappilot.core.model.DeviceEventType
import com.mappilot.core.model.MapPilotEvent
import com.mappilot.core.model.ThermalState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Current degradation state. Recording + sync are never represented here. */
data class DegradationStatus(
    val thermalState: ThermalState = ThermalState.NONE,
    val perceptionPaused: Boolean = false,
    val perceptionHzCap: Int = 0,
    val renderEnabled: Boolean = true,
    val storageAction: StorageAction = StorageAction.NORMAL,
)

/**
 * Central degradation authority. Translates thermal state + storage pressure into
 * perception/render changes via [ThermalPolicy], and records the events. By
 * design it has no handle that can stop recording or sync — those are owned by
 * the recording pipeline and the §10 invariant holds structurally.
 *
 * Storage-critical does request recording stop, but that decision is the
 * RecordingController's (it observes [status]) — protecting the active file from
 * a full disk is the one sanctioned reason to end a session early.
 */
@Singleton
class DegradationController @Inject constructor(
    private val perceptionController: PerceptionController,
    private val configProvider: ConfigProvider,
    private val eventBus: EventBus,
    private val timeSource: TimeSource,
) {
    private val _status = MutableStateFlow(DegradationStatus())
    val status: StateFlow<DegradationStatus> = _status.asStateFlow()

    fun onThermal(state: ThermalState) {
        val plan = ThermalPolicy.plan(state, configProvider.current().perceptionHz)
        perceptionController.applyDegradation(plan)
        _status.value = _status.value.copy(
            thermalState = state,
            perceptionPaused = !plan.perceptionEnabled,
            perceptionHzCap = plan.perceptionHzCap,
            renderEnabled = plan.renderEnabled,
        )
        emit(DeviceEventType.THERMAL_STATE, "thermal=$state ${plan.reason}")
    }

    fun onStorage(action: StorageAction, freeBytes: Long) {
        _status.value = _status.value.copy(storageAction = action)
        if (action == StorageAction.STOP_NEW_PERCEPTION || action == StorageAction.STOP_RECORDING) {
            // Shed perception under storage pressure (recording protected separately).
            perceptionController.applyDegradation(
                com.mappilot.core.common.hardening.DegradationPlan(false, 0, _status.value.renderEnabled, "storage:$action"),
            )
        }
        if (action != StorageAction.NORMAL) emit(DeviceEventType.STORAGE_PRESSURE, "storage=$action free=$freeBytes")
    }

    fun reset() {
        onThermal(ThermalState.NONE)
        _status.value = DegradationStatus()
    }

    private fun emit(type: DeviceEventType, payload: String) {
        val ts = timeSource.elapsedRealtimeNanos()
        eventBus.emit(MapPilotEvent.DeviceEventRaised(ts, DeviceEvent(ts, type, payload)))
        Log.i(Streams.SERVICE, "$type: $payload")
    }
}
