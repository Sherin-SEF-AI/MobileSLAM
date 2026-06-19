package com.mappilot.core.timesync

import com.mappilot.core.model.StreamHealth
import com.mappilot.core.model.SyncWarning
import com.mappilot.core.model.SyncWarningKind
import com.mappilot.core.model.TimestampSource
import kotlin.math.abs

/**
 * Pure, deterministic per-stream timestamp monitor. No clock reads, no Android
 * dependencies — everything it needs is passed in, so its behaviour is fully
 * unit-testable.
 *
 * Responsibilities (§6.1):
 *  - Timebase normalization: measure a per-stream offset on first sample for
 *    non-REALTIME sources, then add it to bring raw timestamps into the unified
 *    `elapsedRealtimeNanos` base.
 *  - Drift detection: track (arrival − normalized) against a baseline; growth
 *    beyond threshold means the stream clock is drifting from the unified clock.
 *  - Latency monitoring: arrival − normalized for the most recent sample.
 *  - Validation: monotonicity / out-of-order rejection, gap detection.
 *  - Accounting: received / dropped / out-of-order / gap counters.
 *
 * Thread-confinement: an instance is single-writer (the SyncEngine serializes
 * calls). State is plain fields, not synchronized.
 */
internal class StreamMonitor(
    private val streamId: String,
    private val source: TimestampSource,
    private val nominalPeriodNs: Long,
    private val driftThresholdNs: Long,
    private val latencyBudgetNs: Long,
    /** Multiple of the nominal period above which a gap is flagged. */
    private val gapFactor: Double = 2.5,
    /** Sliding window size used to estimate rate. */
    private val rateWindow: Int = 64,
    /** Drift warnings are rate-limited to once per this many samples. */
    private val warnEverySamples: Long = 64,
) {
    private var offsetNs: Long = 0
    private var offsetMeasured: Boolean = source == TimestampSource.REALTIME
    private var driftBaselineNs: Long = Long.MIN_VALUE
    private var smoothedDeltaNs: Double = 0.0

    private var lastNormalizedNs: Long = 0
    private var lastArrivalNs: Long = 0
    private var latencyNs: Long = -1
    private var driftNs: Long = 0

    private var received: Long = 0
    private var dropped: Long = 0
    private var outOfOrder: Long = 0
    private var gaps: Long = 0
    private var lastDriftWarnAt: Long = -warnEverySamples
    private var lastLatencyWarnAt: Long = -warnEverySamples

    private val tsWindow = LongArray(rateWindow)
    private var windowCount = 0
    private var windowHead = 0

    /**
     * Process one raw sample. [rawTimestampNs] is the stream's own timestamp;
     * [arrivalRealtimeNs] is `elapsedRealtimeNanos` captured when the sample
     * reached us. Returns any warnings produced by this sample.
     */
    fun onSample(rawTimestampNs: Long, arrivalRealtimeNs: Long): List<SyncWarning> {
        val warnings = ArrayList<SyncWarning>(1)

        if (!offsetMeasured) {
            // First sample of a non-REALTIME stream: anchor its clock to ours.
            offsetNs = arrivalRealtimeNs - rawTimestampNs
            offsetMeasured = true
            if (source == TimestampSource.UNKNOWN) {
                warnings += warn(
                    SyncWarningKind.TIMEBASE_UNKNOWN,
                    "Source clock unknown; measured offset ${offsetNs}ns applied",
                    arrivalRealtimeNs,
                )
            }
        }

        val normalized = rawTimestampNs + offsetNs

        // Out-of-order / non-monotonic: reject, do not advance lastNormalized.
        if (received > 0 && normalized < lastNormalizedNs) {
            outOfOrder++
            warnings += warn(
                SyncWarningKind.OUT_OF_ORDER,
                "ts $normalized < last $lastNormalizedNs",
                arrivalRealtimeNs,
            )
            received++
            return warnings
        }

        // Gap detection against the nominal period.
        if (received > 0 && nominalPeriodNs > 0) {
            val delta = normalized - lastNormalizedNs
            if (delta > (nominalPeriodNs * gapFactor).toLong()) {
                gaps++
                // Estimate how many samples were skipped for dropped accounting.
                val missed = (delta / nominalPeriodNs) - 1
                if (missed > 0) dropped += missed
                warnings += warn(
                    SyncWarningKind.GAP,
                    "gap ${delta}ns (~$missed samples missed)",
                    arrivalRealtimeNs,
                )
            }
        }

        // Latency: how long after the sample's own timestamp it reached us.
        latencyNs = arrivalRealtimeNs - normalized
        if (latencyNs > latencyBudgetNs && received - lastLatencyWarnAt >= warnEverySamples) {
            lastLatencyWarnAt = received
            warnings += warn(
                SyncWarningKind.HIGH_LATENCY,
                "latency ${latencyNs}ns > budget ${latencyBudgetNs}ns",
                arrivalRealtimeNs,
            )
        }

        // Drift: track smoothed (arrival − normalized) against a baseline.
        val delta = (arrivalRealtimeNs - normalized).toDouble()
        smoothedDeltaNs = if (received == 0L) delta else smoothedDeltaNs + DRIFT_ALPHA * (delta - smoothedDeltaNs)
        if (driftBaselineNs == Long.MIN_VALUE && received >= DRIFT_WARMUP) {
            driftBaselineNs = smoothedDeltaNs.toLong()
        }
        if (driftBaselineNs != Long.MIN_VALUE) {
            driftNs = smoothedDeltaNs.toLong() - driftBaselineNs
            if (abs(driftNs) > driftThresholdNs && received - lastDriftWarnAt >= warnEverySamples) {
                lastDriftWarnAt = received
                warnings += warn(
                    SyncWarningKind.DRIFT,
                    "drift ${driftNs}ns > threshold ${driftThresholdNs}ns",
                    arrivalRealtimeNs,
                )
            }
        }

        // Advance state and rate window.
        lastNormalizedNs = normalized
        lastArrivalNs = arrivalRealtimeNs
        tsWindow[windowHead] = normalized
        windowHead = (windowHead + 1) % rateWindow
        if (windowCount < rateWindow) windowCount++
        received++

        return warnings
    }

    /** Record samples dropped upstream (e.g. ring-buffer overflow). */
    fun onDropped(count: Long) {
        if (count > 0) dropped += count
    }

    /** Convert a raw stream timestamp to the unified base using the learned offset. */
    fun normalize(rawTimestampNs: Long): Long = rawTimestampNs + offsetNs

    fun snapshot(): StreamHealth = StreamHealth(
        streamId = streamId,
        source = source,
        appliedOffsetNs = offsetNs,
        rateHz = currentRateHz(),
        driftNs = driftNs,
        latencyNs = latencyNs,
        samplesReceived = received,
        samplesDropped = dropped,
        outOfOrderCount = outOfOrder,
        gapCount = gaps,
        lastTimestampNs = lastNormalizedNs,
    )

    private fun currentRateHz(): Double {
        if (windowCount < 2) return 0.0
        val newestIdx = (windowHead - 1 + rateWindow) % rateWindow
        val oldestIdx = if (windowCount < rateWindow) 0 else windowHead
        val span = tsWindow[newestIdx] - tsWindow[oldestIdx]
        if (span <= 0) return 0.0
        return (windowCount - 1) * 1_000_000_000.0 / span
    }

    private fun warn(kind: SyncWarningKind, detail: String, atNs: Long) =
        SyncWarning(timestampNs = atNs, stream = streamId, kind = kind, detail = detail)

    private companion object {
        const val DRIFT_ALPHA = 0.05
        const val DRIFT_WARMUP = 32L
    }
}
