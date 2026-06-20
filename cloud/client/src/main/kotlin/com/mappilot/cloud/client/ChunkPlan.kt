package com.mappilot.cloud.client

import java.security.MessageDigest

/** One chunk of a file: its index and byte range. */
data class Chunk(val index: Int, val offset: Long, val length: Int)

/**
 * Pure chunking plan for resumable upload. Splits a file into fixed-size chunks,
 * computes which remain given the server's received set, and reports progress.
 * Unit-tested so resume logic is verified independently of the network.
 */
object ChunkPlan {

    fun chunks(totalBytes: Long, chunkSize: Int): List<Chunk> {
        require(chunkSize > 0) { "chunkSize must be > 0" }
        if (totalBytes <= 0) return emptyList()
        val list = ArrayList<Chunk>()
        var offset = 0L
        var index = 0
        while (offset < totalBytes) {
            val len = minOf(chunkSize.toLong(), totalBytes - offset).toInt()
            list.add(Chunk(index, offset, len))
            offset += len
            index++
        }
        return list
    }

    fun totalChunks(totalBytes: Long, chunkSize: Int): Int = chunks(totalBytes, chunkSize).size

    /** Chunks still to send, given the indices the server already has (resume). */
    fun remaining(totalBytes: Long, chunkSize: Int, received: Set<Int>): List<Chunk> =
        chunks(totalBytes, chunkSize).filter { it.index !in received }

    /** Upload progress 0..1 from received-chunk count. */
    fun progress(totalBytes: Long, chunkSize: Int, received: Set<Int>): Double {
        val total = totalChunks(totalBytes, chunkSize)
        return if (total == 0) 1.0 else received.count { it in 0 until total }.toDouble() / total
    }
}

/** Integrity helpers: SHA-256 over a byte range / whole array, lowercase hex. */
object Integrity {
    fun sha256(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(bytes, offset, length)
        return md.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
