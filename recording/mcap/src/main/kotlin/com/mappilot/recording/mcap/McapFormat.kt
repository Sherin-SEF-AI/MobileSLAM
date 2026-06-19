package com.mappilot.recording.mcap

import java.io.ByteArrayOutputStream

/**
 * MCAP format constants and little-endian record-body builder.
 * Reference: https://mcap.dev/spec — all integers little-endian; strings and
 * byte fields carry length prefixes as documented per record.
 */
internal object Mcap {
    /** "\x89MCAP0\r\n" — 8-byte magic at file start and end. */
    val MAGIC = byteArrayOf(0x89.toByte(), 'M'.code.toByte(), 'C'.code.toByte(), 'A'.code.toByte(), 'P'.code.toByte(), '0'.code.toByte(), 0x0D, 0x0A)

    // Record opcodes
    const val OP_HEADER: Int = 0x01
    const val OP_FOOTER: Int = 0x02
    const val OP_SCHEMA: Int = 0x03
    const val OP_CHANNEL: Int = 0x04
    const val OP_MESSAGE: Int = 0x05
    const val OP_CHUNK: Int = 0x06
    const val OP_MESSAGE_INDEX: Int = 0x07
    const val OP_CHUNK_INDEX: Int = 0x08
    const val OP_STATISTICS: Int = 0x0B
    const val OP_METADATA: Int = 0x0C
    const val OP_SUMMARY_OFFSET: Int = 0x0E
    const val OP_DATA_END: Int = 0x0F

    const val PROFILE: String = "mappilot"
}

/** Growable little-endian buffer for assembling a record body. */
internal class LeBuffer {
    private val out = ByteArrayOutputStream(256)

    fun u8(v: Int): LeBuffer { out.write(v and 0xFF); return this }

    fun u16(v: Int): LeBuffer {
        out.write(v and 0xFF); out.write((v ushr 8) and 0xFF); return this
    }

    fun u32(v: Long): LeBuffer {
        out.write((v and 0xFF).toInt())
        out.write(((v ushr 8) and 0xFF).toInt())
        out.write(((v ushr 16) and 0xFF).toInt())
        out.write(((v ushr 24) and 0xFF).toInt())
        return this
    }

    fun u64(v: Long): LeBuffer {
        var x = v
        repeat(8) { out.write((x and 0xFF).toInt()); x = x ushr 8 }
        return this
    }

    /** Length-prefixed UTF-8 string (uint32 byte length + bytes). */
    fun str(s: String): LeBuffer {
        val b = s.toByteArray(Charsets.UTF_8)
        u32(b.size.toLong()); out.write(b); return this
    }

    /** Length-prefixed byte array (uint32 byte length + bytes). */
    fun bytes(b: ByteArray): LeBuffer {
        u32(b.size.toLong()); out.write(b); return this
    }

    /** Raw bytes with no length prefix (e.g. Message data, trailing field). */
    fun raw(b: ByteArray): LeBuffer { out.write(b); return this }

    fun size(): Int = out.size()
    fun toByteArray(): ByteArray = out.toByteArray()
    fun reset() = out.reset()
}
