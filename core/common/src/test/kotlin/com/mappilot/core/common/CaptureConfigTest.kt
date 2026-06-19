package com.mappilot.core.common

import com.google.common.truth.Truth.assertThat
import com.mappilot.core.common.config.CaptureConfig
import com.mappilot.core.common.result.MapPilotResult
import com.mappilot.core.common.result.getOrNull
import com.mappilot.core.common.result.map
import org.junit.Test

class CaptureConfigTest {

    @Test
    fun `default config meets phase-0 budgets`() {
        val config = CaptureConfig()
        assertThat(config.targetFps).isEqualTo(30)
        assertThat(config.imuTargetHz).isAtLeast(100)
        assertThat(config.perceptionHz).isAtMost(config.targetFps)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `perception cadence above fps is rejected`() {
        CaptureConfig(targetFps = 30, perceptionHz = 31)
    }

    @Test
    fun `result map preserves degraded reason`() {
        val degraded: MapPilotResult<Int> = MapPilotResult.Degraded(2, "low light")
        val mapped = degraded.map { it * 10 }
        assertThat(mapped.getOrNull()).isEqualTo(20)
        assertThat((mapped as MapPilotResult.Degraded).reason).isEqualTo("low light")
    }

    @Test
    fun `unavailable result yields null value`() {
        val r: MapPilotResult<Int> = MapPilotResult.Unavailable("gnss", "no fix")
        assertThat(r.getOrNull()).isNull()
    }
}
