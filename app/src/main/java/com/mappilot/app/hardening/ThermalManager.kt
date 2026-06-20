package com.mappilot.app.hardening

import android.content.Context
import android.os.PowerManager
import com.mappilot.core.common.log.Log
import com.mappilot.core.common.log.Streams
import com.mappilot.core.model.ThermalState
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Listens to [PowerManager] thermal status and drives degradation. Under throttle
 * it sheds perception + non-essential render only — recording and sync continue
 * (§10). Maps the platform thermal levels to [ThermalState].
 */
@Singleton
class ThermalManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val degradation: DegradationController,
) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val executor = Executor { it.run() }
    private var listener: PowerManager.OnThermalStatusChangedListener? = null

    fun start() {
        if (listener != null) return
        val l = PowerManager.OnThermalStatusChangedListener { status ->
            degradation.onThermal(status.toThermalState())
        }
        powerManager.addThermalStatusListener(executor, l)
        listener = l
        // Apply the current status immediately.
        degradation.onThermal(powerManager.currentThermalStatus.toThermalState())
        Log.i(Streams.SERVICE, "ThermalManager started")
    }

    fun stop() {
        listener?.let { powerManager.removeThermalStatusListener(it) }
        listener = null
        degradation.reset()
        Log.i(Streams.SERVICE, "ThermalManager stopped")
    }

    private fun Int.toThermalState(): ThermalState = when (this) {
        PowerManager.THERMAL_STATUS_NONE -> ThermalState.NONE
        PowerManager.THERMAL_STATUS_LIGHT -> ThermalState.LIGHT
        PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.MODERATE
        PowerManager.THERMAL_STATUS_SEVERE -> ThermalState.SEVERE
        PowerManager.THERMAL_STATUS_CRITICAL -> ThermalState.CRITICAL
        PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalState.EMERGENCY
        PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalState.SHUTDOWN
        else -> ThermalState.NONE
    }
}
