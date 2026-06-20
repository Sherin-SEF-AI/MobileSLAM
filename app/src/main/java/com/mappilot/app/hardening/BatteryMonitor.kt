package com.mappilot.app.hardening

import android.content.Context
import android.os.BatteryManager
import com.mappilot.core.common.dispatcher.DispatcherProvider
import com.mappilot.core.common.log.Log
import com.mappilot.core.common.log.Streams
import com.mappilot.core.common.time.TimeSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class BatterySample(val percent: Int, val drainPctPerHour: Double, val charging: Boolean)

/**
 * Instruments battery drain during long captures. Samples level periodically and
 * reports drain %/hour so the §10 battery target can be checked on hardware.
 */
@Singleton
class BatteryMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val timeSource: TimeSource,
    private val dispatchers: DispatcherProvider,
) {
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)

    @Volatile var latest: BatterySample? = null
        private set

    private var startPct = -1
    private var startNs = 0L

    fun start() {
        scope.coroutineContext.cancelChildren()
        startPct = level()
        startNs = timeSource.elapsedRealtimeNanos()
        scope.launch {
            while (true) {
                sample()
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    fun stop() = scope.coroutineContext.cancelChildren()

    private fun sample() {
        val now = level()
        val elapsedH = (timeSource.elapsedRealtimeNanos() - startNs) / 3_600_000_000_000.0
        val drain = if (elapsedH > 0.001 && startPct >= 0) (startPct - now) / elapsedH else 0.0
        val charging = batteryManager.isCharging
        latest = BatterySample(now, drain, charging)
        Log.i(Streams.SERVICE, "Battery $now% drain=${"%.1f".format(drain)}%/h charging=$charging")
    }

    private fun level(): Int = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private companion object {
        const val SAMPLE_INTERVAL_MS = 60_000L
    }
}
