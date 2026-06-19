package com.mappilot.core.timesync

import com.google.common.truth.Truth.assertThat
import com.mappilot.core.model.SyncWarningKind
import com.mappilot.core.model.TimestampSource
import org.junit.Test

class StreamMonitorTest {

    private val hz100PeriodNs = 10_000_000L // 100 Hz
    private val driftThreshold = 5_000_000L  // 5 ms
    private val latencyBudget = 5_000_000L   // 5 ms

    private fun monitor(source: TimestampSource = TimestampSource.REALTIME) = StreamMonitor(
        streamId = "test",
        source = source,
        nominalPeriodNs = hz100PeriodNs,
        driftThresholdNs = driftThreshold,
        latencyBudgetNs = latencyBudget,
    )

    @Test
    fun `realtime source applies zero offset`() {
        val m = monitor(TimestampSource.REALTIME)
        m.onSample(rawTimestampNs = 1_000_000_000, arrivalRealtimeNs = 1_000_500_000)
        assertThat(m.snapshot().appliedOffsetNs).isEqualTo(0)
        assertThat(m.normalize(2_000_000_000)).isEqualTo(2_000_000_000)
    }

    @Test
    fun `boottime source measures and applies first-sample offset`() {
        val m = monitor(TimestampSource.BOOTTIME)
        // raw clock is 1_000_000_000 ns behind the unified clock
        m.onSample(rawTimestampNs = 500_000_000, arrivalRealtimeNs = 1_500_000_000)
        assertThat(m.snapshot().appliedOffsetNs).isEqualTo(1_000_000_000)
        assertThat(m.normalize(600_000_000)).isEqualTo(1_600_000_000)
    }

    @Test
    fun `unknown source raises TIMEBASE_UNKNOWN once`() {
        val m = monitor(TimestampSource.UNKNOWN)
        val w = m.onSample(0, 1_000_000)
        assertThat(w.map { it.kind }).contains(SyncWarningKind.TIMEBASE_UNKNOWN)
        val w2 = m.onSample(hz100PeriodNs, 1_000_000 + hz100PeriodNs)
        assertThat(w2.map { it.kind }).doesNotContain(SyncWarningKind.TIMEBASE_UNKNOWN)
    }

    @Test
    fun `rate is computed from monotonic timestamps`() {
        val m = monitor()
        var t = 1_000_000_000L
        repeat(100) {
            m.onSample(t, t) // zero latency
            t += hz100PeriodNs
        }
        assertThat(m.snapshot().rateHz).isWithin(1.0).of(100.0)
    }

    @Test
    fun `out-of-order sample is rejected and counted`() {
        val m = monitor()
        m.onSample(1_000_000_000, 1_000_000_000)
        m.onSample(1_010_000_000, 1_010_000_000)
        val warnings = m.onSample(1_005_000_000, 1_020_000_000) // earlier than last
        assertThat(warnings.map { it.kind }).contains(SyncWarningKind.OUT_OF_ORDER)
        val snap = m.snapshot()
        assertThat(snap.outOfOrderCount).isEqualTo(1)
        // last timestamp not regressed by the rejected sample
        assertThat(snap.lastTimestampNs).isEqualTo(1_010_000_000)
    }

    @Test
    fun `gap detection estimates dropped samples`() {
        val m = monitor()
        m.onSample(1_000_000_000, 1_000_000_000)
        // jump ahead 100 ms == ~10 periods (9 missed)
        val warnings = m.onSample(1_100_000_000, 1_100_000_000)
        assertThat(warnings.map { it.kind }).contains(SyncWarningKind.GAP)
        val snap = m.snapshot()
        assertThat(snap.gapCount).isEqualTo(1)
        assertThat(snap.samplesDropped).isEqualTo(9)
    }

    @Test
    fun `high latency is flagged`() {
        val m = monitor()
        // arrival 10 ms after the sample's own timestamp > 5 ms budget
        val warnings = m.onSample(1_000_000_000, 1_010_000_000)
        assertThat(warnings.map { it.kind }).contains(SyncWarningKind.HIGH_LATENCY)
        assertThat(m.snapshot().latencyNs).isEqualTo(10_000_000)
    }

    @Test
    fun `steady stream produces no drift warning`() {
        val m = monitor()
        var t = 1_000_000_000L
        var warned = false
        repeat(500) {
            // constant 1 ms latency — no drift
            val w = m.onSample(t, t + 1_000_000)
            if (w.any { it.kind == SyncWarningKind.DRIFT }) warned = true
            t += hz100PeriodNs
        }
        assertThat(warned).isFalse()
        assertThat(Math.abs(m.snapshot().driftNs)).isLessThan(driftThreshold)
    }

    @Test
    fun `growing inter-clock offset triggers drift warning`() {
        val m = monitor()
        var t = 1_000_000_000L
        var arrivalExtra = 1_000_000L // start at 1 ms latency
        var warned = false
        repeat(1000) {
            val w = m.onSample(t, t + arrivalExtra)
            if (w.any { it.kind == SyncWarningKind.DRIFT }) warned = true
            t += hz100PeriodNs
            arrivalExtra += 20_000 // +20 us per sample → exceeds 5 ms over the run
        }
        assertThat(warned).isTrue()
    }

    @Test
    fun `dropped samples accumulate from upstream report`() {
        val m = monitor()
        m.onSample(1_000_000_000, 1_000_000_000)
        m.onDropped(5)
        m.onDropped(3)
        assertThat(m.snapshot().samplesDropped).isEqualTo(8)
    }
}
