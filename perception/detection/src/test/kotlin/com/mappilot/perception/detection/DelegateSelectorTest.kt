package com.mappilot.perception.detection

import com.google.common.truth.Truth.assertThat
import com.mappilot.core.common.config.InferenceDelegate
import org.junit.Test

class DelegateSelectorTest {

    @Test
    fun `AUTO uses GPU when supported else CPU`() {
        assertThat(DelegateSelector.choose(InferenceDelegate.AUTO, gpuSupported = true, apiLevel = 34))
            .isEqualTo(DelegateChoice.GPU)
        assertThat(DelegateSelector.choose(InferenceDelegate.AUTO, gpuSupported = false, apiLevel = 34))
            .isEqualTo(DelegateChoice.CPU)
    }

    @Test
    fun `explicit GPU falls back to CPU when unsupported`() {
        assertThat(DelegateSelector.choose(InferenceDelegate.GPU, gpuSupported = false, apiLevel = 34))
            .isEqualTo(DelegateChoice.CPU)
        assertThat(DelegateSelector.choose(InferenceDelegate.GPU, gpuSupported = true, apiLevel = 36))
            .isEqualTo(DelegateChoice.GPU)
    }

    @Test
    fun `explicit CPU always CPU`() {
        assertThat(DelegateSelector.choose(InferenceDelegate.CPU, gpuSupported = true, apiLevel = 30))
            .isEqualTo(DelegateChoice.CPU)
    }

    @Test
    fun `NNAPI only honoured below API 35 (deprecated on 15+)`() {
        assertThat(DelegateSelector.choose(InferenceDelegate.NNAPI, gpuSupported = false, apiLevel = 34))
            .isEqualTo(DelegateChoice.NNAPI)
        assertThat(DelegateSelector.choose(InferenceDelegate.NNAPI, gpuSupported = false, apiLevel = 35))
            .isEqualTo(DelegateChoice.CPU)
    }
}
