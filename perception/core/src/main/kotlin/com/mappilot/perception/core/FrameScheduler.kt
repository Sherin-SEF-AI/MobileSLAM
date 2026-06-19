package com.mappilot.perception.core

/**
 * Throttles the 30 fps capture stream down to the perception cadence (e.g. 8 Hz)
 * and drops frames while one is in flight, so inference never back-pressures
 * capture or recording (§10). Pure and deterministic — clock values are passed
 * in — so the drop policy is unit-tested.
 */
class FrameScheduler(targetHz: Int) {
    private val minIntervalNs: Long = if (targetHz > 0) 1_000_000_000L / targetHz else 0L

    private var lastAcceptedNs: Long = Long.MIN_VALUE
    private var inFlight: Boolean = false

    var accepted: Long = 0; private set
    var dropped: Long = 0; private set

    /**
     * Offer a frame at [timestampNs]. Returns true if it should be processed
     * (cadence elapsed and no inference in flight); caller must call [onComplete]
     * when done. Otherwise the frame is counted as dropped.
     */
    fun offer(timestampNs: Long): Boolean {
        if (inFlight) { dropped++; return false }
        if (lastAcceptedNs != Long.MIN_VALUE && timestampNs - lastAcceptedNs < minIntervalNs) {
            dropped++; return false
        }
        lastAcceptedNs = timestampNs
        inFlight = true
        accepted++
        return true
    }

    fun onComplete() { inFlight = false }

    fun reset() {
        lastAcceptedNs = Long.MIN_VALUE
        inFlight = false
        accepted = 0
        dropped = 0
    }
}
