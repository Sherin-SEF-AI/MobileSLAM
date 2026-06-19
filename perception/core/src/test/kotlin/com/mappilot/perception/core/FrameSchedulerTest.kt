package com.mappilot.perception.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FrameSchedulerTest {

    @Test
    fun `throttles to target cadence`() {
        val s = FrameScheduler(targetHz = 10) // 100 ms min interval
        assertThat(s.offer(0)).isTrue(); s.onComplete()
        assertThat(s.offer(50_000_000)).isFalse()  // 50 ms later → drop
        assertThat(s.offer(100_000_000)).isTrue(); s.onComplete() // 100 ms → accept
        assertThat(s.accepted).isEqualTo(2)
        assertThat(s.dropped).isEqualTo(1)
    }

    @Test
    fun `drops while inference in flight regardless of cadence`() {
        val s = FrameScheduler(targetHz = 1000) // ~1 ms interval
        assertThat(s.offer(0)).isTrue() // accepted, in flight (no onComplete)
        assertThat(s.offer(10_000_000)).isFalse() // would pass cadence but in-flight
        assertThat(s.dropped).isEqualTo(1)
        s.onComplete()
        assertThat(s.offer(20_000_000)).isTrue()
    }

    @Test
    fun `first frame always accepted`() {
        val s = FrameScheduler(targetHz = 5)
        assertThat(s.offer(123_456)).isTrue()
    }
}
