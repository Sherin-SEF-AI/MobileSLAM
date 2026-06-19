package com.mappilot.recording.mcap

import com.google.common.truth.Truth.assertThat
import com.mappilot.core.model.ImuChannel
import com.mappilot.core.model.ImuSample
import java.io.File
import org.junit.Test

class McapRecovererTest {

    /** Write several sealed chunks, then simulate a crash by truncating the tail. */
    private fun writeThenTruncate(file: File): Int {
        file.outputStream().use { os ->
            val w = McapTripWriter(os, "crash-test", chunkTargetBytes = 1024)
            w.start()
            var ts = 1_000_000L
            repeat(500) {
                w.writeImu(ImuSample(ImuChannel.ACCEL, ts, 0.1f, 9.8f, 0.2f, 3))
                ts += 5_000_000L
            }
            w.seal() // force several sealed, flushed chunks
            // Intentionally DO NOT call finish() — emulates a crash before finalize.
        }
        val sealedLength = file.length()
        // Corrupt further: append a partial record header to simulate a torn write.
        file.appendBytes(byteArrayOf(Mcap.OP_CHUNK.toByte(), 0xFF.toByte(), 0xFF.toByte()))
        return sealedLength.toInt()
    }

    @Test
    fun `recovers a valid indexed file from an unsealed crash`() {
        val file = File.createTempFile("trip", ".mcap")
        writeThenTruncate(file)

        // Before recovery: no footer.
        val before = McapReader().read(file.readBytes(), tolerateTruncation = true)
        assertThat(before.hasFooter).isFalse()
        assertThat(before.messages).isNotEmpty()

        val outcome = McapRecoverer.recover(file)
        assertThat(outcome).isInstanceOf(McapRecoverer.Outcome.Recovered::class.java)

        // After recovery: a fully valid, footered, indexed file.
        val after = McapReader().read(file.readBytes())
        assertThat(after.hasFooter).isTrue()
        assertThat(after.truncated).isFalse()
        assertThat(after.allChunkCrcValid).isTrue()
        assertThat(after.summaryChunkIndexCount).isEqualTo(after.chunkCount)
        // No messages lost from the sealed chunks.
        assertThat(after.messages.size).isEqualTo(before.messages.size)

        // Original broken file preserved for forensics.
        assertThat(File(file.parentFile, file.name + ".broken").exists()).isTrue()
        file.delete()
        File(file.parentFile, file.name + ".broken").delete()
    }

    @Test
    fun `already-valid file is left untouched`() {
        val file = File.createTempFile("valid", ".mcap")
        file.outputStream().use { os ->
            val w = McapTripWriter(os, "ok")
            w.start()
            w.writeImu(ImuSample(ImuChannel.ACCEL, 1000, 0f, 0f, 0f, 3))
            w.finish()
        }
        assertThat(McapRecoverer.recover(file)).isEqualTo(McapRecoverer.Outcome.AlreadyValid)
        file.delete()
    }
}
