package com.mappilot.recording.mcap

import java.io.BufferedOutputStream
import java.io.OutputStream
import java.util.zip.CRC32

/** A schema registered in the file. */
internal data class McapSchema(val id: Int, val name: String, val encoding: String, val data: ByteArray)

/** A channel registered in the file. */
internal data class McapChannel(val id: Int, val schemaId: Int, val topic: String, val messageEncoding: String)

/**
 * Streaming MCAP writer: chunked, indexed, CRC-checked. Produces a file that the
 * reference MCAP tooling reads with a valid index.
 *
 * Layout: Magic, Header, [Schema…][Channel…] (data-section copies so the stream
 * is self-describing), then repeated (Chunk, MessageIndex…) groups sealed on
 * size or on demand, DataEnd (with data-section CRC), summary section (Schema…,
 * Channel…, ChunkIndex…, Statistics), SummaryOffsets, Footer, Magic.
 *
 * Single-threaded by contract: the recording writer thread is the only caller.
 */
internal class McapWriter(
    rawOut: OutputStream,
    private val library: String,
    private val chunkTargetBytes: Int = DEFAULT_CHUNK_BYTES,
) {
    private val out = BufferedOutputStream(rawOut, 1 shl 16)
    private var position: Long = 0
    private val dataCrc = CRC32()
    private var dataSectionActive = false
    private var finished = false

    private val schemas = LinkedHashMap<Int, McapSchema>()
    private val channels = LinkedHashMap<Int, McapChannel>()
    private var nextSchemaId = 1
    private var nextChannelId = 0

    // Current (open) chunk state
    private val chunkRecords = LeBuffer()
    private val chunkMsgIndex = HashMap<Int, MutableList<LongArray>>() // channelId -> [ [logTime, offset], ... ]
    private var chunkStartTime = Long.MAX_VALUE
    private var chunkEndTime = Long.MIN_VALUE
    private var chunkMessageCount = 0

    // Summary accounting
    private val chunkIndexes = ArrayList<ChunkIndexEntry>()
    private val channelMessageCounts = HashMap<Int, Long>()
    private val sequenceByChannel = HashMap<Int, Long>()
    private var totalMessageCount = 0L
    private var fileStartTime = Long.MAX_VALUE
    private var fileEndTime = Long.MIN_VALUE

    private data class ChunkIndexEntry(
        val startTime: Long,
        val endTime: Long,
        val chunkStartOffset: Long,
        val chunkLength: Long,
        val messageIndexOffsets: LinkedHashMap<Int, Long>,
        val messageIndexLength: Long,
        val uncompressedSize: Long,
    )

    val bytesWritten: Long get() = position

    fun writeHeader() {
        out.write(Mcap.MAGIC); position += Mcap.MAGIC.size
        val body = LeBuffer().str(Mcap.PROFILE).str(library)
        writeRecord(Mcap.OP_HEADER, body.toByteArray())
        // Data section begins after the Header record.
        dataSectionActive = true
    }

    fun addSchema(name: String, encoding: String, data: ByteArray): Int {
        val id = nextSchemaId++
        val schema = McapSchema(id, name, encoding, data)
        schemas[id] = schema
        writeRecord(Mcap.OP_SCHEMA, schemaBody(schema))
        return id
    }

    fun addChannel(schemaId: Int, topic: String, messageEncoding: String): Int {
        val id = nextChannelId++
        val channel = McapChannel(id, schemaId, topic, messageEncoding)
        channels[id] = channel
        writeRecord(Mcap.OP_CHANNEL, channelBody(channel))
        return id
    }

    /** Append a message to the current chunk; seals the chunk when it grows large. */
    fun writeMessage(channelId: Int, logTimeNs: Long, data: ByteArray, publishTimeNs: Long = logTimeNs) {
        check(!finished) { "writer finished" }
        val seq = (sequenceByChannel[channelId] ?: 0L)
        sequenceByChannel[channelId] = seq + 1

        val offsetInChunk = chunkRecords.size().toLong()
        val body = LeBuffer()
            .u16(channelId)
            .u32(seq and 0xFFFFFFFFL)
            .u64(logTimeNs)
            .u64(publishTimeNs)
            .raw(data)
        // Records inside a chunk are framed exactly like top-level records.
        appendRecordTo(chunkRecords, Mcap.OP_MESSAGE, body.toByteArray())

        chunkMsgIndex.getOrPut(channelId) { ArrayList() }.add(longArrayOf(logTimeNs, offsetInChunk))
        if (logTimeNs < chunkStartTime) chunkStartTime = logTimeNs
        if (logTimeNs > chunkEndTime) chunkEndTime = logTimeNs
        chunkMessageCount++
        channelMessageCounts[channelId] = (channelMessageCounts[channelId] ?: 0L) + 1
        totalMessageCount++
        if (logTimeNs < fileStartTime) fileStartTime = logTimeNs
        if (logTimeNs > fileEndTime) fileEndTime = logTimeNs

        if (chunkRecords.size() >= chunkTargetBytes) sealChunk()
    }

    /** Seal the open chunk (write Chunk + MessageIndex records) and flush to disk. */
    fun sealChunk() {
        if (chunkMessageCount == 0) { out.flush(); return }
        val records = chunkRecords.toByteArray()
        val crc = CRC32().apply { update(records) }.value

        val chunkBody = LeBuffer()
            .u64(chunkStartTime)
            .u64(chunkEndTime)
            .u64(records.size.toLong())   // uncompressed_size
            .u32(crc)                     // uncompressed_crc
            .str("")                      // compression: none
            .u64(records.size.toLong())   // records byte length (uint64-prefixed payload)
            .raw(records)

        val chunkStartOffset = position
        writeRecord(Mcap.OP_CHUNK, chunkBody.toByteArray())
        val chunkLength = position - chunkStartOffset

        // MessageIndex records immediately follow the chunk.
        val indexOffsets = LinkedHashMap<Int, Long>()
        val indexStart = position
        for ((channelId, entries) in chunkMsgIndex) {
            val recs = LeBuffer()
            for (e in entries) recs.u64(e[0]).u64(e[1])
            val body = LeBuffer().u16(channelId).bytes(recs.toByteArray())
            indexOffsets[channelId] = position
            writeRecord(Mcap.OP_MESSAGE_INDEX, body.toByteArray())
        }
        val indexLength = position - indexStart

        chunkIndexes.add(
            ChunkIndexEntry(
                startTime = chunkStartTime,
                endTime = chunkEndTime,
                chunkStartOffset = chunkStartOffset,
                chunkLength = chunkLength,
                messageIndexOffsets = indexOffsets,
                messageIndexLength = indexLength,
                uncompressedSize = records.size.toLong(),
            ),
        )

        // Reset open-chunk state and persist so a crash leaves a recoverable file.
        chunkRecords.reset()
        chunkMsgIndex.clear()
        chunkStartTime = Long.MAX_VALUE
        chunkEndTime = Long.MIN_VALUE
        chunkMessageCount = 0
        out.flush()
    }

    /** Finalize: DataEnd, summary (schemas, channels, chunk indexes, statistics), offsets, footer, magic. */
    fun finish() {
        if (finished) return
        sealChunk()

        // DataEnd terminates the data section; its CRC covers all data-section bytes.
        dataSectionActive = false
        val dataEnd = LeBuffer().u32(dataCrc.value)
        writeRecord(Mcap.OP_DATA_END, dataEnd.toByteArray())

        val summaryStart = position
        val schemaGroupStart = position
        for (s in schemas.values) writeRecord(Mcap.OP_SCHEMA, schemaBody(s))
        val schemaGroupLen = position - schemaGroupStart

        val channelGroupStart = position
        for (c in channels.values) writeRecord(Mcap.OP_CHANNEL, channelBody(c))
        val channelGroupLen = position - channelGroupStart

        val chunkIndexGroupStart = position
        for (ci in chunkIndexes) writeRecord(Mcap.OP_CHUNK_INDEX, chunkIndexBody(ci))
        val chunkIndexGroupLen = position - chunkIndexGroupStart

        val statsGroupStart = position
        writeRecord(Mcap.OP_STATISTICS, statisticsBody())
        val statsGroupLen = position - statsGroupStart

        val summaryOffsetStart = position
        writeSummaryOffset(Mcap.OP_SCHEMA, schemaGroupStart, schemaGroupLen)
        writeSummaryOffset(Mcap.OP_CHANNEL, channelGroupStart, channelGroupLen)
        writeSummaryOffset(Mcap.OP_CHUNK_INDEX, chunkIndexGroupStart, chunkIndexGroupLen)
        writeSummaryOffset(Mcap.OP_STATISTICS, statsGroupStart, statsGroupLen)

        val footer = LeBuffer()
            .u64(summaryStart)
            .u64(summaryOffsetStart)
            .u32(0L) // summary_crc: not available (spec-permitted)
        writeRecord(Mcap.OP_FOOTER, footer.toByteArray())

        out.write(Mcap.MAGIC); position += Mcap.MAGIC.size
        out.flush()
        finished = true
    }

    // --- record bodies ---

    private fun schemaBody(s: McapSchema): ByteArray =
        LeBuffer().u16(s.id).str(s.name).str(s.encoding).bytes(s.data).toByteArray()

    private fun channelBody(c: McapChannel): ByteArray =
        LeBuffer().u16(c.id).u16(c.schemaId).str(c.topic).str(c.messageEncoding)
            .u32(0L) // metadata map: empty (0-byte length)
            .toByteArray()

    private fun chunkIndexBody(ci: ChunkIndexEntry): ByteArray {
        val offs = LeBuffer()
        for ((ch, off) in ci.messageIndexOffsets) offs.u16(ch).u64(off)
        return LeBuffer()
            .u64(ci.startTime).u64(ci.endTime)
            .u64(ci.chunkStartOffset).u64(ci.chunkLength)
            .bytes(offs.toByteArray())          // message_index_offsets map
            .u64(ci.messageIndexLength)
            .str("")                            // compression
            .u64(ci.uncompressedSize)           // compressed_size (== uncompressed, none)
            .u64(ci.uncompressedSize)           // uncompressed_size
            .toByteArray()
    }

    private fun statisticsBody(): ByteArray {
        val counts = LeBuffer()
        for ((ch, n) in channelMessageCounts) counts.u16(ch).u64(n)
        val startT = if (fileStartTime == Long.MAX_VALUE) 0 else fileStartTime
        val endT = if (fileEndTime == Long.MIN_VALUE) 0 else fileEndTime
        return LeBuffer()
            .u64(totalMessageCount)
            .u16(schemas.size)
            .u32(channels.size.toLong())
            .u32(0L) // attachment_count
            .u32(0L) // metadata_count
            .u32(chunkIndexes.size.toLong())
            .u64(startT)
            .u64(endT)
            .bytes(counts.toByteArray())
            .toByteArray()
    }

    private fun writeSummaryOffset(groupOpcode: Int, groupStart: Long, groupLength: Long) {
        if (groupLength <= 0) return
        val body = LeBuffer().u8(groupOpcode).u64(groupStart).u64(groupLength)
        writeRecord(Mcap.OP_SUMMARY_OFFSET, body.toByteArray())
    }

    // --- low-level framing ---

    private fun writeRecord(opcode: Int, body: ByteArray) {
        val header = LeBuffer().u8(opcode).u64(body.size.toLong()).toByteArray()
        writeBytes(header)
        writeBytes(body)
    }

    private fun appendRecordTo(buf: LeBuffer, opcode: Int, body: ByteArray) {
        buf.u8(opcode).u64(body.size.toLong()).raw(body)
    }

    private fun writeBytes(b: ByteArray) {
        out.write(b)
        position += b.size
        if (dataSectionActive) dataCrc.update(b)
    }

    private companion object {
        const val DEFAULT_CHUNK_BYTES = 1 shl 20 // 1 MiB
    }
}
