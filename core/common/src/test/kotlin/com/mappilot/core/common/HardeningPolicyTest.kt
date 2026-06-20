package com.mappilot.core.common

import com.google.common.truth.Truth.assertThat
import com.mappilot.core.common.hardening.StorageAction
import com.mappilot.core.common.hardening.StoragePolicy
import com.mappilot.core.common.hardening.ThermalPolicy
import com.mappilot.core.model.ThermalState
import org.junit.Test

class HardeningPolicyTest {

    @Test
    fun `thermal plan degrades perception and render monotonically`() {
        val configured = 8
        val none = ThermalPolicy.plan(ThermalState.NONE, configured)
        val moderate = ThermalPolicy.plan(ThermalState.MODERATE, configured)
        val severe = ThermalPolicy.plan(ThermalState.SEVERE, configured)
        val critical = ThermalPolicy.plan(ThermalState.CRITICAL, configured)

        assertThat(none.perceptionHzCap).isEqualTo(8)
        assertThat(moderate.perceptionHzCap).isAtMost(none.perceptionHzCap)
        assertThat(severe.perceptionHzCap).isAtMost(moderate.perceptionHzCap)
        assertThat(severe.renderEnabled).isFalse()
        assertThat(critical.perceptionEnabled).isFalse()
        assertThat(critical.perceptionHzCap).isEqualTo(0)
    }

    @Test
    fun `thermal plan never has a field that could disable recording or sync`() {
        // The DegradationPlan exposes only perception + render — recording/sync
        // are structurally un-degradable. This asserts the contract surface.
        val fields = DegradationPlanFields.names()
        assertThat(fields).containsExactly("perceptionEnabled", "perceptionHzCap", "renderEnabled", "reason")
        assertThat(fields).doesNotContain("recordingEnabled")
        assertThat(fields).doesNotContain("syncEnabled")
    }

    @Test
    fun `storage action escalates as free space shrinks`() {
        assertThat(StoragePolicy.action(50L * 1024 * 1024 * 1024)).isEqualTo(StorageAction.NORMAL)   // 50 GB
        assertThat(StoragePolicy.action(4L * 1024 * 1024 * 1024)).isEqualTo(StorageAction.WARN)       // 4 GB
        assertThat(StoragePolicy.action(1L * 1024 * 1024 * 1024)).isEqualTo(StorageAction.STOP_NEW_PERCEPTION) // 1 GB
        assertThat(StoragePolicy.action(100L * 1024 * 1024)).isEqualTo(StorageAction.STOP_RECORDING)  // 100 MB
    }

    @Test
    fun `format bytes is human readable base-2`() {
        assertThat(StoragePolicy.formatBytes(512)).isEqualTo("512 B")
        assertThat(StoragePolicy.formatBytes(1536)).isEqualTo("1.5 KB")
        assertThat(StoragePolicy.formatBytes(2L * 1024 * 1024 * 1024)).isEqualTo("2.0 GB")
    }
}

private object DegradationPlanFields {
    fun names(): List<String> =
        com.mappilot.core.common.hardening.DegradationPlan::class.java.declaredFields
            .map { it.name }.filter { it != "\$stable" }
}
