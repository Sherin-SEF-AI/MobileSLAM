package com.mappilot.cloud.client

import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** HTTP transport for the cloud contract. Implementations talk to the backend. */
interface CloudTransport {
    fun createUpload(req: CreateUploadRequest): CreateUploadResponse
    fun status(uploadId: String): UploadStatus
    /** Returns true on success, false on 409 checksum mismatch (caller retries). */
    fun putChunk(uploadId: String, index: Int, bytes: ByteArray, sha256: String): Boolean
    fun complete(uploadId: String): CompleteResponse
    fun createJob(req: CreateJobRequest): CreateJobResponse
    fun job(jobId: String): JobStatus
    fun fetchResult(jobId: String): Pair<ByteArray, String?> // bytes + provenance
}

/** `HttpURLConnection`-based transport (no extra deps; works on device and in JVM tests). */
class HttpCloudTransport(
    private val baseUrl: String,
    private val authToken: String? = null,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : CloudTransport {

    override fun createUpload(req: CreateUploadRequest): CreateUploadResponse =
        postJson("/uploads", json.encodeToString(CreateUploadRequest.serializer(), req)) {
            json.decodeFromString(CreateUploadResponse.serializer(), it)
        }

    override fun status(uploadId: String): UploadStatus =
        getJson("/uploads/$uploadId") { json.decodeFromString(UploadStatus.serializer(), it) }

    override fun putChunk(uploadId: String, index: Int, bytes: ByteArray, sha256: String): Boolean {
        val conn = open("/uploads/$uploadId/chunks/$index", "PUT")
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        conn.setRequestProperty("X-Chunk-SHA256", sha256)
        conn.doOutput = true
        conn.outputStream.use { it.write(bytes) }
        return when (conn.responseCode) {
            HttpURLConnection.HTTP_OK -> true
            HttpURLConnection.HTTP_CONFLICT -> false
            else -> throw IOException("putChunk $index failed: ${conn.responseCode}")
        }.also { conn.disconnect() }
    }

    override fun complete(uploadId: String): CompleteResponse =
        postJson("/uploads/$uploadId/complete", "{}") { json.decodeFromString(CompleteResponse.serializer(), it) }

    override fun createJob(req: CreateJobRequest): CreateJobResponse =
        postJson("/jobs", json.encodeToString(CreateJobRequest.serializer(), req)) {
            json.decodeFromString(CreateJobResponse.serializer(), it)
        }

    override fun job(jobId: String): JobStatus =
        getJson("/jobs/$jobId") { json.decodeFromString(JobStatus.serializer(), it) }

    override fun fetchResult(jobId: String): Pair<ByteArray, String?> {
        val conn = open("/jobs/$jobId/result", "GET")
        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            conn.disconnect(); throw IOException("result not ready: ${conn.responseCode}")
        }
        val provenance = conn.getHeaderField("X-Provenance")
        val bytes = conn.inputStream.use { it.readBytes() }
        conn.disconnect()
        return bytes to provenance
    }

    private fun open(path: String, method: String): HttpURLConnection {
        val conn = URL("$baseUrl$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        authToken?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        return conn
    }

    private fun <T> postJson(path: String, body: String, parse: (String) -> T): T {
        val conn = open(path, "POST")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray()) }
        if (conn.responseCode !in 200..299) {
            val err = conn.errorStream?.readBytes()?.decodeToString()
            conn.disconnect(); throw IOException("POST $path → ${conn.responseCode}: $err")
        }
        val resp = conn.inputStream.use { it.readBytes().decodeToString() }
        conn.disconnect()
        return parse(resp)
    }

    private fun <T> getJson(path: String, parse: (String) -> T): T {
        val conn = open(path, "GET")
        if (conn.responseCode !in 200..299) { conn.disconnect(); throw IOException("GET $path → ${conn.responseCode}") }
        val resp = conn.inputStream.use { it.readBytes().decodeToString() }
        conn.disconnect()
        return parse(resp)
    }
}
