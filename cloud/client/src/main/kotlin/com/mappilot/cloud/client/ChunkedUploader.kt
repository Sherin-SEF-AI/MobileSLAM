package com.mappilot.cloud.client

import com.mappilot.core.common.log.Log
import com.mappilot.core.common.log.Streams
import java.io.File
import java.io.RandomAccessFile

/**
 * Resumable, integrity-checked chunked uploader. Resumes by asking the server
 * which chunks it already has and sending only the rest; each chunk is verified
 * by SHA-256 server-side and re-sent on a 409 mismatch. Survives a mid-upload
 * failure: re-invoking [upload] with the same [existingUploadId] continues where
 * it left off — no chunk is re-sent unnecessarily.
 */
class ChunkedUploader(
    private val transport: CloudTransport,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    private val maxChunkRetries: Int = 3,
) {
    data class Result(val uploadId: String, val artifactId: String, val chunksSent: Int)

    fun interface ProgressListener { fun onProgress(uploadId: String, sent: Int, total: Int) }

    /**
     * @param existingUploadId resume an in-flight session (from the persisted job),
     *   or null to create a new session.
     */
    fun upload(
        file: File,
        tripId: Long,
        existingUploadId: String? = null,
        progress: ProgressListener? = null,
    ): Result {
        require(file.exists()) { "file not found: $file" }
        val totalBytes = file.length()
        val plan = ChunkPlan.chunks(totalBytes, chunkSize)
        val wholeSha = fileSha256(file)

        val session = if (existingUploadId != null) {
            val st = transport.status(existingUploadId)
            CreateUploadResponse(existingUploadId, chunkSize, st.receivedChunks)
        } else {
            transport.createUpload(CreateUploadRequest(tripId, file.name, totalBytes, chunkSize, wholeSha))
        }

        val received = session.receivedChunks.toMutableSet()
        var sent = 0
        RandomAccessFile(file, "r").use { raf ->
            for (chunk in plan) {
                if (chunk.index in received) continue
                val bytes = ByteArray(chunk.length)
                raf.seek(chunk.offset)
                raf.readFully(bytes)
                val sha = Integrity.sha256(bytes)
                uploadChunkWithRetry(session.uploadId, chunk.index, bytes, sha)
                received.add(chunk.index)
                sent++
                progress?.onProgress(session.uploadId, received.size, plan.size)
            }
        }

        val complete = transport.complete(session.uploadId)
        Log.i(Streams.CLOUD, "Upload ${file.name} complete: ${complete.artifactId} ($sent/${plan.size} chunks sent)")
        return Result(session.uploadId, complete.artifactId, sent)
    }

    private fun uploadChunkWithRetry(uploadId: String, index: Int, bytes: ByteArray, sha: String) {
        var attempt = 0
        while (true) {
            val ok = transport.putChunk(uploadId, index, bytes, sha)
            if (ok) return
            if (++attempt >= maxChunkRetries) error("chunk $index failed checksum after $maxChunkRetries retries")
            Log.w(Streams.CLOUD, "chunk $index checksum mismatch, retry $attempt")
        }
    }

    private fun fileSha256(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val DEFAULT_CHUNK_SIZE = 8 * 1024 * 1024 // 8 MiB
    }
}
