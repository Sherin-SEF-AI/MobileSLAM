package com.mappilot.core.common.capture

/**
 * A startable/stoppable producer of a sensor stream. Implementations run their
 * own threads and emit onto the event bus / ring buffers; they never block the
 * caller and never touch disk or DB on their callback threads.
 */
interface CaptureSource {
    /** Stable stream/source name, for logging and health reporting. */
    val name: String

    /** Begin producing. Idempotent — a second call while running is a no-op. */
    fun start()

    /** Stop producing and release resources. Idempotent. */
    fun stop()

    val isRunning: Boolean
}
