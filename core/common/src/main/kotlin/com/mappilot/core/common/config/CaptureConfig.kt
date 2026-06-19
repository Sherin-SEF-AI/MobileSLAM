package com.mappilot.core.common.config

/**
 * Capture/runtime configuration with production defaults that meet the §10
 * performance budgets. Surfaced to Settings; persisted via a ConfigStore in a
 * later phase.
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
) {
    init {
        require(targetFps in 1..120) { "targetFps out of range: $targetFps" }
        require(imuTargetHz in 50..1000) { "imuTargetHz out of range: $imuTargetHz" }
        require(perceptionHz in 1..targetFps) { "perceptionHz must be <= targetFps" }
    }
}

/** Provides the active [CaptureConfig]. Backed by DataStore in a later phase. */
interface ConfigProvider {
    fun current(): CaptureConfig
}
