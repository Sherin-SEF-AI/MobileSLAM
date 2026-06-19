package com.mappilot.core.common.buffer

import java.util.concurrent.atomic.AtomicLong

/**
 * Lock-free single-producer / single-consumer ring buffer.
 *
 * The hot-path contract (§5): a sensor/camera callback (the single producer)
 * calls [offer] and never blocks, never touches disk/DB. A consumer thread (the
 * MCAP writer in Phase 2, or a batcher now) calls [poll]. When full, [offer]
 * drops the incoming item and increments [dropped] — back-pressure can never
 * stall the producer.
 *
 * Capacity is rounded up to a power of two so index wrapping is a mask. Safe for
 * exactly one producer thread and one consumer thread.
 */
class RingBuffer<T>(requestedCapacity: Int) {
    private val capacity: Int = Integer.highestOneBit(requestedCapacity - 1).coerceAtLeast(1) shl 1
    private val mask: Int = capacity - 1

    @Suppress("UNCHECKED_CAST")
    private val slots: Array<Any?> = arrayOfNulls<Any?>(capacity)

    // head = next index to read (consumer), tail = next index to write (producer).
    private val head = AtomicLong(0)
    private val tail = AtomicLong(0)
    private val droppedCount = AtomicLong(0)

    val dropped: Long get() = droppedCount.get()
    val size: Int get() = (tail.get() - head.get()).toInt()
    val maxCapacity: Int get() = capacity

    /** Producer side. Returns false (and counts a drop) if the buffer is full. */
    fun offer(item: T): Boolean {
        val t = tail.get()
        if (t - head.get() >= capacity) {
            droppedCount.incrementAndGet()
            return false
        }
        slots[(t and mask.toLong()).toInt()] = item
        tail.lazySet(t + 1) // publish after the write
        return true
    }

    /** Consumer side. Returns null when empty. */
    @Suppress("UNCHECKED_CAST")
    fun poll(): T? {
        val h = head.get()
        if (h >= tail.get()) return null
        val idx = (h and mask.toLong()).toInt()
        val item = slots[idx] as T?
        slots[idx] = null
        head.lazySet(h + 1)
        return item
    }

    /** Drain up to [max] items into [sink]; returns the count drained. */
    inline fun drain(max: Int = Int.MAX_VALUE, sink: (T) -> Unit): Int {
        var n = 0
        while (n < max) {
            val item = poll() ?: break
            sink(item)
            n++
        }
        return n
    }
}
