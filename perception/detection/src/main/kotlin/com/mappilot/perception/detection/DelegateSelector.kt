package com.mappilot.perception.detection

import com.mappilot.core.common.config.InferenceDelegate

/** The accelerator actually chosen for inference (after capability + policy checks). */
enum class DelegateChoice { GPU, NNAPI, CPU }

/**
 * Pure decision for which inference backend to attempt, given the user preference,
 * whether the GPU delegate is supported on this device, and the API level.
 *
 * Policy: NNAPI is deprecated on Android 15+ (API 35) so it is only honoured when
 * explicitly selected on an older device; AUTO prefers GPU when available and
 * otherwise CPU/XNNPACK. Unit-tested independently of any TFLite runtime.
 */
object DelegateSelector {
    fun choose(pref: InferenceDelegate, gpuSupported: Boolean, apiLevel: Int): DelegateChoice = when (pref) {
        InferenceDelegate.CPU -> DelegateChoice.CPU
        InferenceDelegate.GPU -> if (gpuSupported) DelegateChoice.GPU else DelegateChoice.CPU
        InferenceDelegate.NNAPI -> if (apiLevel < 35) DelegateChoice.NNAPI else DelegateChoice.CPU
        InferenceDelegate.AUTO -> if (gpuSupported) DelegateChoice.GPU else DelegateChoice.CPU
    }
}
