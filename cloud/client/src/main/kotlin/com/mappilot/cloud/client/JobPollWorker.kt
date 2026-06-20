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

/**
 * Periodic poller that advances cloud jobs left in PROCESSING after the inline
 * upload worker's bounded poll window. Without it, a job that finishes server-side
 * minutes later would stay PROCESSING forever in the UI. Queries each pending
 * job's status and mirrors the result (READY/FAILED/…) to its row.
 *
 * Re-queries by the persisted server jobId (the row's remoteId). Never fabricates
 * results — a failed query leaves the row untouched for the next run.
 */
@HiltWorker
class JobPollWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: MapPilotRepository,
    private val cloudConfig: CloudConfig,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val pending = repository.uploadJobsInState("PROCESSING").filter { it.remoteId != null }
        if (pending.isEmpty()) return Result.success()

        val transport = HttpCloudTransport(cloudConfig.baseUrl)
        var advanced = 0
        for (job in pending) {
            val jobId = job.remoteId ?: continue
            runCatching {
                val status = transport.job(jobId)
                val appState = CloudStateMapper.fromJobState(status.state).name
                if (appState != job.state) {
                    repository.updateUpload(job.id, appState, job.bytesSent, jobId)
                    advanced++
                }
            }.onFailure {
                Log.w(Streams.CLOUD, "Job poll failed for ${job.id} ($jobId): ${it.message}")
            }
        }
        if (advanced > 0) Log.i(Streams.CLOUD, "Job poller advanced $advanced/${pending.size} jobs")
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "mappilot-job-poller"
    }
}
