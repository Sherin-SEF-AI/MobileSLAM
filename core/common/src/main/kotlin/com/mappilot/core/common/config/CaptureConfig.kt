package com.mappilot.core.common.config

/**
 * On-device inference backend for the perception model. AUTO probes the GPU
 * delegate and falls back to CPU/XNNPACK. NNAPI is legacy (deprecated on
 * Android 15+) and only honoured when explicitly selected.
 */
enum class InferenceDelegate { AUTO, GPU, CPU, NNAPI }

/**
 * Capture/runtime configuration with production defaults that meet the §10
 * performance budgets. Surfaced to (and edited from) Settings and persisted via
 * a DataStore-backed [ConfigProvider].
 */
data class CaptureConfig(
    val videoWidth: Int = 1920,
    val videoHeight: Int = 1080,
    val targetFps: Int = 30,
    val imuTargetHz: Int = 200,
    val perceptionHz: Int = 8,
    /** Seal + flush an MCAP chunk on this interval for crash survival. */
    val mcapChunkSealIntervalMs: Long = 2_000,
    /** Roll over to a new MCAP segment file at this size for long sessions. */
    val mcapSegmentRolloverBytes: Long = 512L * 1024 * 1024,
    /** Cross-stream offset above which a DRIFT SyncWarning is raised. */
    val syncDriftThresholdNs: Long = 5_000_000, // 5 ms
    /** Capture→write latency above which a HIGH_LATENCY warning is raised. */
    val captureLatencyBudgetNs: Long = 5_000_000, // 5 ms
    /** Preferred perception inference backend. */
    val inferenceDelegate: InferenceDelegate = InferenceDelegate.AUTO,
) {
    init {
        require(targetFps in 1..120) { "targetFps out of range: $targetFps" }
        require(imuTargetHz in 50..1000) { "imuTargetHz out of range: $imuTargetHz" }
        require(perceptionHz in 1..targetFps) { "perceptionHz must be <= targetFps" }
    }
}

/**
 * Provides the active [CaptureConfig]. The default implementation is
 * DataStore-backed; [current] returns a synchronous snapshot of persisted state.
 */
interface ConfigProvider {
    fun current(): CaptureConfig
}
