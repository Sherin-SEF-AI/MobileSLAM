package com.mappilot.core.common.hardening

import com.mappilot.core.model.ThermalState

/**
 * What to degrade under thermal pressure. By construction this plan can ONLY
 * affect perception and non-essential rendering — there is no field to disable
 * recording or sync, so the §10 invariant ("never degrade recording or sync") is
 * enforced structurally, not just by convention.
 */
data class DegradationPlan(
    val perceptionEnabled: Boolean,
    val perceptionHzCap: Int,
    val renderEnabled: Boolean,
    val reason: String,
)

/**
 * Maps device thermal state to a degradation plan. Monotonic: hotter never
 * relaxes a restriction. Recording + sync are intentionally absent from the plan.
 */
object ThermalPolicy {

    fun plan(state: ThermalState, configuredPerceptionHz: Int): DegradationPlan = when (state) {
        ThermalState.NONE, ThermalState.LIGHT ->
            DegradationPlan(true, configuredPerceptionHz, true, "nominal")
        ThermalState.MODERATE ->
            DegradationPlan(true, minOf(configuredPerceptionHz, 5), true, "moderate: capped perception")
        ThermalState.SEVERE ->
            DegradationPlan(true, minOf(configuredPerceptionHz, 2), false, "severe: min perception, render off")
        ThermalState.CRITICAL, ThermalState.EMERGENCY, ThermalState.SHUTDOWN ->
            DegradationPlan(false, 0, false, "critical: perception + render off (recording/sync untouched)")
    }
}
