package com.mappilot.core.common

import com.google.common.truth.Truth.assertThat
import com.mappilot.core.common.buffer.RingBuffer
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class RingBufferTest {

    @Test
    fun `capacity rounds up to power of two`() {
        assertThat(RingBuffer<Int>(100).maxCapacity).isEqualTo(128)
        assertThat(RingBuffer<Int>(128).maxCapacity).isEqualTo(128)
        assertThat(RingBuffer<Int>(1).maxCapacity).isEqualTo(2)
    }

    @Test
    fun `fifo order preserved`() {
        val rb = RingBuffer<Int>(8)
        repeat(5) { assertThat(rb.offer(it)).isTrue() }
        val out = buildList { repeat(5) { add(rb.poll()) } }
        assertThat(out).containsExactly(0, 1, 2, 3, 4).inOrder()
        assertThat(rb.poll()).isNull()
    }

    @Test
    fun `full buffer drops and counts instead of blocking`() {
        val rb = RingBuffer<Int>(4) // capacity 4
        repeat(4) { assertThat(rb.offer(it)).isTrue() }
        assertThat(rb.offer(99)).isFalse()
        assertThat(rb.dropped).isEqualTo(1)
    }

    @Test
    fun `single producer single consumer transfers all items`() {
        val rb = RingBuffer<Long>(1024)
        val total = 200_000L
        val received = ArrayList<Long>(total.toInt())
        val done = CountDownLatch(1)

        val consumer = thread {
            var count = 0L
            while (count < total) {
                val v = rb.poll()
                if (v != null) {
                    received.add(v)
                    count++
                }
            }
            done.countDown()
        }

        var produced = 0L
        while (produced < total) {
            if (rb.offer(produced)) produced++
        }
        done.await(10, TimeUnit.SECONDS)
        consumer.join()

        assertThat(received.size.toLong()).isEqualTo(total)
        // strictly increasing == nothing reordered or duplicated
        assertThat(received).isInOrder()
        assertThat(received.first()).isEqualTo(0L)
        assertThat(received.last()).isEqualTo(total - 1)
    }
}
