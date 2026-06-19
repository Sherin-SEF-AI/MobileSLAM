package com.mappilot.core.timesync

import com.mappilot.core.common.bus.EventBus
import com.mappilot.core.common.config.ConfigProvider
import com.mappilot.core.common.log.Log
import com.mappilot.core.common.log.Streams
import com.mappilot.core.common.time.TimeSource
import com.mappilot.core.model.MapPilotEvent
import com.mappilot.core.model.StreamHealth
import com.mappilot.core.model.SyncHealth
import com.mappilot.core.model.SyncWarning
import com.mappilot.core.model.TimestampSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Normalizes every sensor stream to the unified `elapsedRealtimeNanos` base and
 * continuously monitors sync health (drift, latency, gaps, ordering, drops).
 *
 * Producers call [registerStream] once, then [recordSample] for every sample
 * with the sample's own timestamp and the arrival time. The engine is the only
 * writer per stream; [recordSample] is cheap and lock-free per stream
 * (single-writer monitors). Warnings are pushed to the [EventBus] and the latest
 * [SyncHealth] is published on [health].
 */
@Singleton
class SyncEngine @Inject constructor(
    private val timeSource: TimeSource,
    private val eventBus: EventBus,
    private val configProvider: ConfigProvider,
) {
    private val monitors = ConcurrentHashMap<String, StreamMonitor>()
    private val recentWarnings = ArrayDeque<SyncWarning>()

    private val _health = MutableStateFlow(SyncHealth.EMPTY)
    val health: StateFlow<SyncHealth> = _health.asStateFlow()

    /**
     * Register a stream before recording samples.
     * @param expectedRateHz nominal rate, used for gap detection (0 = unknown).
     */
    fun registerStream(streamId: String, source: TimestampSource, expectedRateHz: Double) {
        val config = configProvider.current()
        val nominalPeriodNs = if (expectedRateHz > 0) (1_000_000_000.0 / expectedRateHz).toLong() else 0
        monitors[streamId] = StreamMonitor(
            streamId = streamId,
            source = source,
            nominalPeriodNs = nominalPeriodNs,
            driftThresholdNs = config.syncDriftThresholdNs,
            latencyBudgetNs = config.captureLatencyBudgetNs,
        )
        Log.i(Streams.SYNC, "Registered stream '$streamId' source=$source rate=${expectedRateHz}Hz")
        publish()
    }

    /**
     * Record one sample. Returns the timestamp normalized to the unified base so
     * the caller can stamp the value it forwards downstream (MCAP/bus).
     */
    fun recordSample(streamId: String, rawTimestampNs: Long, arrivalRealtimeNs: Long): Long {
        val monitor = monitors[streamId] ?: run {
            Log.w(Streams.SYNC, "recordSample for unregistered stream '$streamId'")
            return rawTimestampNs
        }
        val warnings = monitor.onSample(rawTimestampNs, arrivalRealtimeNs)
        if (warnings.isNotEmpty()) emitWarnings(warnings)
        publish()
        return monitor.normalize(rawTimestampNs)
    }

    /** Convenience overload that stamps arrival with the unified clock now. */
    fun recordSample(streamId: String, rawTimestampNs: Long): Long =
        recordSample(streamId, rawTimestampNs, timeSource.elapsedRealtimeNanos())

    /** Report samples lost upstream (e.g. ring-buffer overflow). */
    fun recordDropped(streamId: String, count: Long) {
        monitors[streamId]?.onDropped(count)
        publish()
    }

    fun snapshot(streamId: String): StreamHealth? = monitors[streamId]?.snapshot()

    private fun emitWarnings(warnings: List<SyncWarning>) {
        for (w in warnings) {
            Log.w(Streams.SYNC, "[${w.stream}] ${w.kind}: ${w.detail}")
            eventBus.emit(MapPilotEvent.SyncWarningRaised(w.timestampNs, w))
            synchronized(recentWarnings) {
                recentWarnings.addLast(w)
                while (recentWarnings.size > MAX_RECENT_WARNINGS) recentWarnings.removeFirst()
            }
        }
    }

    private fun publish() {
        val streams = monitors.mapValues { it.value.snapshot() }
        val warnings = synchronized(recentWarnings) { recentWarnings.toList() }
        _health.value = SyncHealth(streams, warnings)
    }

    private companion object {
        const val MAX_RECENT_WARNINGS = 32
    }
}
