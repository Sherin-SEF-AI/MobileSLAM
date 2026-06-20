package com.mappilot.core.timesync

import com.google.common.truth.Truth.assertThat
import com.mappilot.core.common.bus.SharedFlowEventBus
import com.mappilot.core.common.config.DefaultConfigProvider
import com.mappilot.core.common.time.TimeSource
import com.mappilot.core.model.TimestampSource
import org.junit.Test

/**
 * Verifies the health-publish coalescing added for performance: at high sample
 * rates the engine must not rebuild + emit [com.mappilot.core.model.SyncHealth]
 * on every sample, only at most ~20 Hz (plus forced publishes on warnings).
 */
class SyncEnginePublishThrottleTest {

    /** Arrival timestamps are supplied explicitly, so this clock is never read. */
    private object ZeroClock : TimeSource {
        override fun elapsedRealtimeNanos(): Long = 0
        override fun wallClockMillis(): Long = 0
    }

    private fun engine() = SyncEngine(ZeroClock, SharedFlowEventBus(), DefaultConfigProvider())

    @Test
    fun `rapid samples within the publish window do not refresh health`() {
        val e = engine()
        // REALTIME source + unknown rate (0) + raw==arrival => no sync warnings,
        // so nothing forces an early publish.
        e.registerStream("imu", TimestampSource.REALTIME, 0.0)

        // 20 samples all inside the first 50 ms window.
        for (i in 1..20) e.recordSample("imu", i * 1_000_000L, i * 1_000_000L)

        // Health is the (stale) registration snapshot: no publish happened.
        assertThat(e.health.value.streams["imu"]!!.samplesReceived).isEqualTo(0L)
    }

    @Test
    fun `a sample past the window publishes the accumulated state`() {
        val e = engine()
        e.registerStream("imu", TimestampSource.REALTIME, 0.0)
        for (i in 1..20) e.recordSample("imu", i * 1_000_000L, i * 1_000_000L)

        // Crossing the 50 ms boundary triggers exactly one publish with all 21.
        e.recordSample("imu", 60_000_000L, 60_000_000L)
        assertThat(e.health.value.streams["imu"]!!.samplesReceived).isEqualTo(21L)
    }
}
