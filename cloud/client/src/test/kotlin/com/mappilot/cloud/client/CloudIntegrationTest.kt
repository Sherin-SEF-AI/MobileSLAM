package com.mappilot.cloud.client

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.security.MessageDigest
import org.junit.Test

/**
 * Exercises [ChunkedUploader] (resume, integrity retry) and the job lifecycle
 * against an in-memory implementation of the cloud contract. The transport
 * verifies per-chunk SHA-256 and the whole-file checksum exactly as the backend
 * must, so the uploader's resume/integrity behaviour is proven end-to-end. The
 * real [HttpCloudTransport] is validated against the `/server-dev` reference
 * server (see that directory's README).
 */
class CloudIntegrationTest {

    private fun tempFile(bytes: Int): File =
        File.createTempFile("artifact", ".bin").apply {
            writeBytes(ByteArray(bytes) { (it % 251).toByte() }); deleteOnExit()
        }

    @Test
    fun `full upload transfers all chunks and verifies whole-file checksum`() {
        val server = FakeCloudTransport()
        val file = tempFile(25_000)
        val result = ChunkedUploader(server, chunkSize = 8_000).upload(file, tripId = 1)
        assertThat(result.chunksSent).isEqualTo(4) // 8000*3 + 1000
        assertThat(result.artifactId).isNotEmpty()
        assertThat(server.isComplete(result.uploadId)).isTrue()
        assertThat(server.wholeChecksumOk(result.uploadId)).isTrue()
    }

    @Test
    fun `resumes after a mid-upload drop without re-sending received chunks`() {
        val server = FakeCloudTransport()
        val file = tempFile(25_000)
        val uploader = ChunkedUploader(server, chunkSize = 8_000)

        server.failChunkIndexFrom = 2 // drop chunks 2,3 on the first pass
        val first = runCatching { uploader.upload(file, tripId = 1) }
        assertThat(first.isFailure).isTrue()
        val uploadId = server.lastUploadId!!
        assertThat(server.receivedCount(uploadId)).isEqualTo(2)

        server.failChunkIndexFrom = Int.MAX_VALUE // network recovers
        val result = uploader.upload(file, tripId = 1, existingUploadId = uploadId)
        assertThat(result.chunksSent).isEqualTo(2) // only the remaining chunks
        assertThat(server.isComplete(uploadId)).isTrue()
        assertThat(server.wholeChecksumOk(uploadId)).isTrue()
    }

    @Test
    fun `re-sends a chunk on server checksum mismatch`() {
        val server = FakeCloudTransport()
        server.corruptCheckOnceForIndex = 0
        val result = ChunkedUploader(server, chunkSize = 8_000).upload(tempFile(10_000), tripId = 1)
        assertThat(server.isComplete(result.uploadId)).isTrue()
        assertThat(server.rejections).isEqualTo(1)
    }

    @Test
    fun `job lifecycle moves to ready with provenance`() {
        val server = FakeCloudTransport()
        val up = ChunkedUploader(server, chunkSize = 8_000).upload(tempFile(4_000), tripId = 7)
        val job = server.createJob(CreateJobRequest(up.artifactId, JobType.SFM_REFINE.name, 7))
        assertThat(CloudStateMapper.fromJobState(job.state)).isEqualTo(CloudState.PROCESSING)

        var state = server.job(job.jobId)
        var polls = 0
        while (state.state != "READY" && polls < 10) { state = server.job(job.jobId); polls++ }
        assertThat(state.state).isEqualTo("READY")
        assertThat(state.provenance).isEqualTo("CLOUD_REFINED")

        val (bytes, provenance) = server.fetchResult(job.jobId)
        assertThat(provenance).isEqualTo("CLOUD_REFINED")
        assertThat(bytes).isNotEmpty()
    }
}

/** In-memory implementation of the cloud contract for testing the uploader. */
private class FakeCloudTransport : CloudTransport {
    private class Upload(val totalBytes: Long, val chunkSize: Int, val wholeSha: String) {
        val chunks = HashMap<Int, ByteArray>()
        var complete = false
    }
    private val uploads = HashMap<String, Upload>()
    private val jobPolls = HashMap<String, Int>()
    private var seq = 0

    var lastUploadId: String? = null
    var failChunkIndexFrom = Int.MAX_VALUE
    var corruptCheckOnceForIndex = -1
    var rejections = 0

    fun receivedCount(id: String) = uploads[id]?.chunks?.size ?: 0
    fun isComplete(id: String) = uploads[id]?.complete == true
    fun wholeChecksumOk(id: String): Boolean {
        val u = uploads[id] ?: return false
        val md = MessageDigest.getInstance("SHA-256")
        u.chunks.toSortedMap().forEach { (_, b) -> md.update(b) }
        return md.digest().joinToString("") { "%02x".format(it) } == u.wholeSha
    }

    override fun createUpload(req: CreateUploadRequest): CreateUploadResponse {
        val id = "u_${seq++}"
        uploads[id] = Upload(req.totalBytes, req.chunkSize, req.sha256)
        lastUploadId = id
        return CreateUploadResponse(id, req.chunkSize, emptyList())
    }

    override fun status(uploadId: String): UploadStatus {
        val u = uploads.getValue(uploadId)
        val total = Math.ceil(u.totalBytes.toDouble() / u.chunkSize).toInt()
        return UploadStatus(uploadId, u.chunks.keys.sorted(), total, "UPLOADING")
    }

    override fun putChunk(uploadId: String, index: Int, bytes: ByteArray, sha256: String): Boolean {
        if (index >= failChunkIndexFrom) throw java.io.IOException("simulated network drop on chunk $index")
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (actual != sha256 || index == corruptCheckOnceForIndex) {
            if (index == corruptCheckOnceForIndex) corruptCheckOnceForIndex = -1
            rejections++
            return false // 409 → client retries
        }
        uploads.getValue(uploadId).chunks[index] = bytes
        return true
    }

    override fun complete(uploadId: String): CompleteResponse {
        val u = uploads.getValue(uploadId)
        val total = Math.ceil(u.totalBytes.toDouble() / u.chunkSize).toInt()
        check(u.chunks.size >= total) { "incomplete: ${u.chunks.size}/$total" }
        u.complete = true
        return CompleteResponse("a_$uploadId", "COMPLETE")
    }

    override fun createJob(req: CreateJobRequest): CreateJobResponse {
        val id = "j_${seq++}"; jobPolls[id] = 0
        return CreateJobResponse(id, "QUEUED")
    }

    override fun job(jobId: String): JobStatus {
        val polls = (jobPolls[jobId] ?: 0) + 1
        jobPolls[jobId] = polls
        val state = if (polls >= 3) "READY" else "PROCESSING"
        return JobStatus(jobId, state, polls / 3.0, "CLOUD_REFINED")
    }

    override fun fetchResult(jobId: String): Pair<ByteArray, String?> {
        check((jobPolls[jobId] ?: 0) >= 3) { "not ready" }
        return "refined-result-marker".toByteArray() to "CLOUD_REFINED"
    }
}
