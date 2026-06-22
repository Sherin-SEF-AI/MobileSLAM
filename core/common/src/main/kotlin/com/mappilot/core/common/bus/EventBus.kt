package com.mappilot.core.common.bus

import com.mappilot.core.model.MapPilotEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-process typed event bus over a [SharedFlow]. Producers on hot paths emit
 * non-suspending via [tryEmit]; the buffer drops oldest on overflow so a slow
 * subscriber can never back-pressure a sensor callback.
 */
interface EventBus {
    val events: SharedFlow<MapPilotEvent>

    /** Non-blocking emit. Returns false if the event was dropped (buffer full). */
    fun emit(event: MapPilotEvent): Boolean
}

@Singleton
class SharedFlowEventBus @Inject constructor() : EventBus {
    private val _events = MutableSharedFlow<MapPilotEvent>(
        replay = 0,
        extraBufferCapacity = BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val events: SharedFlow<MapPilotEvent> = _events.asSharedFlow()

    override fun emit(event: MapPilotEvent): Boolean = _events.tryEmit(event)

    private companion object {
        // Sized so a brief consumer stall can't evict the sparse but critical low-rate
        // events (e.g. ~1 Hz GnssFixReceived, which the VIO->ENU fusion needs to align)
        // under the steady few-hundred-Hz IMU/rotation/pose traffic. DROP_OLDEST still
        // protects sensor callbacks from back-pressure.
        const val BUFFER_CAPACITY = 1024
    }
}
