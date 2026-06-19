package com.mappilot.recording.mcap

import java.util.zip.CRC32

/** Minimal MCAP reader: enough to validate output and drive crash recovery. */
internal class McapReader {

    data class Schema(val id: Int, val name: String, val encoding: String, val data: ByteArray)
    data class Channel(val id: Int, val schemaId: Int, val topic: String, val messageEncoding: String)
    data class Message(val channelId: Int, val logTimeNs: Long, val publishTimeNs: Long, val data: ByteArray)

    data class Result(
        val schemas: Map<Int, Schema>,
        val channels: Map<Int, Channel>,
        val messages: List<Message>,
        val chunkCount: Int,
        val allChunkCrcValid: Boolean,
        val truncated: Boolean,
        val hasFooter: Boolean,
        val summaryChunkIndexCount: Int,
    )

    /**
     * Parse [bytes]. When [tolerateTruncation] is true, a record cut short by a
     * crash stops parsing cleanly and sets [Result.truncated]; otherwise it throws.
     */
    fun read(bytes: ByteArray, tolerateTruncation: Boolean = false): Result {
        val schemas = LinkedHashMap<Int, Schema>()
        val channels = LinkedHashMap<Int, Channel>()
        val messages = ArrayList<Message>()
        var chunkCount = 0
        var allCrcValid = true
        var truncated = false
        var hasFooter = false
        var summaryChunkIndexCount = 0

        val c = Cursor(bytes)
        require(c.matchMagic()) { "missing leading MCAP magic" }

        while (c.remaining() >= 9) {
            val recStart = c.pos
            val opcode = c.u8()
            val len = c.u64()
            if (len < 0 || c.remaining() < len) {
                if (tolerateTruncation) { truncated = true; c.pos = recStart; break }
                error("record opcode=$opcode length=$len exceeds remaining ${c.remaining()}")
            }
            val end = c.pos + len.toInt()
            when (opcode) {
                Mcap.OP_SCHEMA -> {
                    val id = c.u16(); val name = c.str(); val enc = c.str(); val data = c.lenBytes()
                    schemas[id] = Schema(id, name, enc, data)
                }
                Mcap.OP_CHANNEL -> {
                    val id = c.u16(); val schemaId = c.u16(); val topic = c.str(); val enc = c.str()
                    channels[id] = Channel(id, schemaId, topic, enc)
                }
                Mcap.OP_CHUNK -> {
                    chunkCount++
                    c.u64(); c.u64() // start, end time
                    val uncompressedSize = c.u64()
                    val crc = c.u32()
                    val compression = c.str()
                    val recordsLen = c.u64()
                    val records = c.take(recordsLen.toInt())
                    check(compression.isEmpty()) { "unexpected chunk compression '$compression'" }
                    check(records.size.toLong() == uncompressedSize) { "chunk size mismatch" }
                    if (crc != 0L) {
                        val actual = CRC32().apply { update(records) }.value
                        if (actual != crc) allCrcValid = false
                    }
                    parseChunkRecords(records, messages)
                }
                Mcap.OP_CHUNK_INDEX -> summaryChunkIndexCount++
                Mcap.OP_FOOTER -> hasFooter = true
                else -> Unit // skip message-index, statistics, summary-offset, data-end, etc.
            }
            c.pos = end
            if (opcode == Mcap.OP_FOOTER) break
        }
        return Result(schemas, channels, messages, chunkCount, allCrcValid, truncated, hasFooter, summaryChunkIndexCount)
    }

    private fun parseChunkRecords(records: ByteArray, out: MutableList<Message>) {
        val c = Cursor(records)
        while (c.remaining() >= 9) {
            val opcode = c.u8()
            val len = c.u64()
            if (c.remaining() < len) break
            val end = c.pos + len.toInt()
            if (opcode == Mcap.OP_MESSAGE) {
                val channelId = c.u16()
                c.u32() // sequence
                val logTime = c.u64()
                val pubTime = c.u64()
                val data = c.take((end - c.pos).toInt())
                out.add(Message(channelId, logTime, pubTime, data))
            }
            c.pos = end
        }
    }

    /** Little-endian cursor over a byte array. */
    private class Cursor(val b: ByteArray) {
        var pos: Int = 0
        fun remaining(): Long = (b.size - pos).toLong()
        fun u8(): Int = b[pos++].toInt() and 0xFF
        fun u16(): Int { val v = u8() or (u8() shl 8); return v }
        fun u32(): Long {
            var v = 0L
            for (i in 0 until 4) v = v or ((b[pos++].toLong() and 0xFF) shl (8 * i))
            return v
        }
        fun u64(): Long {
            var v = 0L
            for (i in 0 until 8) v = v or ((b[pos++].toLong() and 0xFF) shl (8 * i))
            return v
        }
        fun take(n: Int): ByteArray { val r = b.copyOfRange(pos, pos + n); pos += n; return r }
        fun str(): String { val n = u32().toInt(); return String(take(n), Charsets.UTF_8) }
        fun lenBytes(): ByteArray { val n = u32().toInt(); return take(n) }
        fun matchMagic(): Boolean {
            if (b.size < Mcap.MAGIC.size) return false
            for (i in Mcap.MAGIC.indices) if (b[i] != Mcap.MAGIC[i]) return false
            pos = Mcap.MAGIC.size
            return true
        }
    }
}
