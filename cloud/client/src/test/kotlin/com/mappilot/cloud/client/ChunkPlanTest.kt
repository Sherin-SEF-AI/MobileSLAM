package com.mappilot.cloud.client

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChunkPlanTest {

    @Test
    fun `splits into fixed-size chunks with a smaller tail`() {
        val chunks = ChunkPlan.chunks(totalBytes = 2500, chunkSize = 1000)
        assertThat(chunks).hasSize(3)
        assertThat(chunks[0]).isEqualTo(Chunk(0, 0, 1000))
        assertThat(chunks[2]).isEqualTo(Chunk(2, 2000, 500)) // tail
    }

    @Test
    fun `exact multiple has no tail`() {
        val chunks = ChunkPlan.chunks(3000, 1000)
        assertThat(chunks).hasSize(3)
        assertThat(chunks.last().length).isEqualTo(1000)
    }

    @Test
    fun `remaining skips received chunks for resume`() {
        val remaining = ChunkPlan.remaining(2500, 1000, received = setOf(0, 1))
        assertThat(remaining.map { it.index }).containsExactly(2)
    }

    @Test
    fun `progress reflects received fraction`() {
        assertThat(ChunkPlan.progress(2500, 1000, setOf(0, 1))).isWithin(1e-9).of(2.0 / 3)
        assertThat(ChunkPlan.progress(2500, 1000, setOf(0, 1, 2))).isEqualTo(1.0)
        assertThat(ChunkPlan.progress(0, 1000, emptySet())).isEqualTo(1.0)
    }

    @Test
    fun `sha256 is stable and order-sensitive`() {
        val a = Integrity.sha256("hello".toByteArray())
        assertThat(a).isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824")
        assertThat(Integrity.sha256("hellp".toByteArray())).isNotEqualTo(a)
    }

    @Test
    fun `state mapping follows the contract`() {
        assertThat(CloudStateMapper.fromUpload(0, 10)).isEqualTo(CloudState.QUEUED)
        assertThat(CloudStateMapper.fromUpload(5, 10)).isEqualTo(CloudState.UPLOADING)
        assertThat(CloudStateMapper.fromUpload(10, 10)).isEqualTo(CloudState.PROCESSING)
        assertThat(CloudStateMapper.fromJobState("PROCESSING")).isEqualTo(CloudState.PROCESSING)
        assertThat(CloudStateMapper.fromJobState("READY")).isEqualTo(CloudState.READY)
        assertThat(CloudStateMapper.fromJobState("FAILED")).isEqualTo(CloudState.FAILED)
    }
}
