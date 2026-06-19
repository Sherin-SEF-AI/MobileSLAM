package com.mappilot.core.common.result

/**
 * Explicit result type for operations that can fail or be unavailable.
 *
 * Prime directive: capabilities that cannot run surface [Unavailable] or
 * [Degraded] — never a silent fake. Callers must handle every arm.
 */
sealed interface MapPilotResult<out T> {
    data class Success<T>(val value: T) : MapPilotResult<T>

    /** Operation failed with a recoverable or reportable error. */
    data class Failure(val error: Throwable, val message: String? = null) : MapPilotResult<Nothing>

    /** A required capability/sensor/model is not present on this device. */
    data class Unavailable(val capability: String, val reason: String) : MapPilotResult<Nothing>

    /** Operating, but below spec — value is real but quality is reduced. */
    data class Degraded<T>(val value: T, val reason: String) : MapPilotResult<T>
}

inline fun <T, R> MapPilotResult<T>.map(transform: (T) -> R): MapPilotResult<R> = when (this) {
    is MapPilotResult.Success -> MapPilotResult.Success(transform(value))
    is MapPilotResult.Degraded -> MapPilotResult.Degraded(transform(value), reason)
    is MapPilotResult.Failure -> this
    is MapPilotResult.Unavailable -> this
}

fun <T> MapPilotResult<T>.getOrNull(): T? = when (this) {
    is MapPilotResult.Success -> value
    is MapPilotResult.Degraded -> value
    else -> null
}

inline fun <T> runCatchingResult(block: () -> T): MapPilotResult<T> = try {
    MapPilotResult.Success(block())
} catch (t: Throwable) {
    if (t is kotlinx.coroutines.CancellationException) throw t
    MapPilotResult.Failure(t, t.message)
}
