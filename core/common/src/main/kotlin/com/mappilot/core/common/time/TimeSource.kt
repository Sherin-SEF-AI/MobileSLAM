package com.mappilot.core.common.time

import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one and only synchronization clock for MapPilot.
 *
 * Every sensor stream is normalized to [android.os.SystemClock.elapsedRealtimeNanos].
 * `System.currentTimeMillis()` is FORBIDDEN for sync (it is wall-clock, subject
 * to NTP jumps). It is acceptable only for human-readable wall timestamps in
 * metadata, which is why [wallClockMillis] is separated and named explicitly.
 */
interface TimeSource {
    /** Monotonic, sync-grade nanoseconds since boot (includes deep sleep). */
    fun elapsedRealtimeNanos(): Long

    /** Wall-clock millis — for display/metadata ONLY, never for stream alignment. */
    fun wallClockMillis(): Long
}

@Singleton
class SystemTimeSource @Inject constructor() : TimeSource {
    override fun elapsedRealtimeNanos(): Long = SystemClock.elapsedRealtimeNanos()
    override fun wallClockMillis(): Long = System.currentTimeMillis()
}

/** Nanoseconds in one second / millisecond — shared to avoid magic numbers. */
const val NANOS_PER_SECOND: Long = 1_000_000_000L
const val NANOS_PER_MILLI: Long = 1_000_000L
