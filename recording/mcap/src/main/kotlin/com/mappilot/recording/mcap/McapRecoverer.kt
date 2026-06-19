package com.mappilot.recording.mcap

import com.mappilot.core.common.log.Log
import com.mappilot.core.common.log.Streams
import java.io.File

/**
 * Recovers an MCAP file left unsealed by a crash. The writer flushes after every
 * sealed chunk, so a killed session leaves a valid prefix (magic, header,
 * schemas, channels, complete chunks) followed by a truncated tail and no
 * summary/footer.
 *
 * Recovery reads every complete chunk from the broken file (tolerating the
 * truncated tail) and rewrites a fresh, fully-indexed MCAP via the tested
 * [McapWriter] — guaranteeing validity "up to the last sealed chunk".
 */
object McapRecoverer {

    sealed interface Outcome {
        /** File already had a footer and was not truncated. */
        data object AlreadyValid : Outcome
        data class Recovered(val messages: Int, val chunks: Int) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    fun recover(file: File): Outcome {
        if (!file.exists() || file.length() < Mcap.MAGIC.size) {
            return Outcome.Failed("file missing or too small")
        }
        return try {
            val bytes = file.readBytes()
            val r = McapReader().read(bytes, tolerateTruncation = true)
            if (r.hasFooter && !r.truncated) {
                Log.i(Streams.RECORDING, "MCAP ${file.name} already valid")
                return Outcome.AlreadyValid
            }
            Log.w(
                Streams.RECORDING,
                "Recovering ${file.name}: ${r.chunkCount} chunks, ${r.messages.size} messages, truncated=${r.truncated}",
            )

            val tmp = File(file.parentFile, file.name + ".recovering")
            tmp.outputStream().buffered().use { os ->
                val w = McapWriter(os, library = "mappilot-recovery")
                w.writeHeader()
                val schemaMap = HashMap<Int, Int>()
                for (s in r.schemas.values) schemaMap[s.id] = w.addSchema(s.name, s.encoding, s.data)
                val channelMap = HashMap<Int, Int>()
                for (ch in r.channels.values) {
                    val newSchema = schemaMap[ch.schemaId] ?: continue
                    channelMap[ch.id] = w.addChannel(newSchema, ch.topic, ch.messageEncoding)
                }
                for (m in r.messages) {
                    val newCh = channelMap[m.channelId] ?: continue
                    w.writeMessage(newCh, m.logTimeNs, m.data, m.publishTimeNs)
                }
                w.finish()
            }

            // Keep the broken original alongside for forensics; promote the recovered file.
            val broken = File(file.parentFile, file.name + ".broken")
            if (broken.exists()) broken.delete()
            file.renameTo(broken)
            tmp.renameTo(file)
            Outcome.Recovered(r.messages.size, r.chunkCount)
        } catch (e: Exception) {
            Log.e(Streams.RECORDING, e, "MCAP recovery failed for ${file.name}")
            Outcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
}
