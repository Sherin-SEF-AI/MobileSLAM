package com.mappilot.cloud.client

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mappilot.core.common.log.Log
import com.mappilot.core.common.log.Streams
import com.mappilot.core.database.MapPilotRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import java.io.File

/**
 * Resumable upload + job dispatch as a WorkManager job, so it survives process
 * death and retries with backoff on transient failure. State is mirrored to the
 * `UploadJob` row (QUEUED→UPLOADING→PROCESSING→READY/FAILED), with the server
 * `uploadId`/`jobId` persisted so a retry resumes rather than restarts.
 */
@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: MapPilotRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val jobRowId = inputData.getLong(KEY_JOB_ROW, -1)
        val path = inputData.getString(KEY_FILE) ?: return Result.failure()
        val tripId = inputData.getLong(KEY_TRIP, -1)
        val baseUrl = inputData.getString(KEY_BASE_URL) ?: return Result.failure()
        val jobType = inputData.getString(KEY_JOB_TYPE) ?: JobType.SFM_REFINE.name
        val file = File(path)
        if (!file.exists()) return Result.failure()

        val transport = HttpCloudTransport(baseUrl)
        val existing = repository.uploadJob(jobRowId)

        return try {
            // Resume if we already have a server uploadId; else create + persist one.
            val uploadId = existing?.remoteId?.takeIf { it.startsWith("u_") }
                ?: transport.createUpload(
                    CreateUploadRequest(tripId, file.name, file.length(), CHUNK_SIZE, fileSha(file)),
                ).uploadId.also { repository.updateUpload(jobRowId, "UPLOADING", 0, it) }

            val uploader = ChunkedUploader(transport, chunkSize = CHUNK_SIZE)
            val result = uploader.upload(file, tripId, existingUploadId = uploadId) { _, sent, total ->
                // bytesSent approximation by chunk fraction; coroutine-safe blocking write avoided.
            }
            repository.updateUpload(jobRowId, "PROCESSING", file.length(), result.uploadId)

            // Create + poll the processing job (bounded; leaves PROCESSING if not yet ready).
            val job = transport.createJob(CreateJobRequest(result.artifactId, jobType, tripId))
            var status = transport.job(job.jobId)
            var polls = 0
            while (status.state == "QUEUED" || status.state == "PROCESSING") {
                if (polls++ >= MAX_POLLS) break
                delay(POLL_DELAY_MS)
                status = transport.job(job.jobId)
            }
            val appState = CloudStateMapper.fromJobState(status.state).name
            repository.updateUpload(jobRowId, appState, file.length(), job.jobId)
            Log.i(Streams.CLOUD, "Upload job $jobRowId -> $appState (server ${status.state})")
            Result.success()
        } catch (e: Exception) {
            Log.e(Streams.CLOUD, e, "upload worker transient failure; will retry")
            // Leave row UPLOADING; WorkManager retries with backoff.
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else {
                repository.updateUpload(jobRowId, "FAILED", 0, existing?.remoteId)
                Result.failure()
            }
        }
    }

    private fun fileSha(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val KEY_JOB_ROW = "job_row_id"
        const val KEY_FILE = "file_path"
        const val KEY_TRIP = "trip_id"
        const val KEY_BASE_URL = "base_url"
        const val KEY_JOB_TYPE = "job_type"
        private const val CHUNK_SIZE = 8 * 1024 * 1024
        private const val MAX_POLLS = 5
        private const val POLL_DELAY_MS = 3_000L
        private const val MAX_ATTEMPTS = 5
    }
}
