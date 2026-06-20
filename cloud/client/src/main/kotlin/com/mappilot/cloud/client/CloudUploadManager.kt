package com.mappilot.cloud.client

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.mappilot.core.common.log.Log
import com.mappilot.core.common.log.Streams
import com.mappilot.core.database.MapPilotRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enqueues resumable uploads. Each artifact gets an `UploadJob` row (QUEUED) and
 * a unique WorkManager job constrained to a network connection, with exponential
 * backoff. Recording/capture are never blocked by upload (§2).
 */
@Singleton
class CloudUploadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MapPilotRepository,
) {
    suspend fun enqueue(tripId: Long, file: File, jobType: JobType, baseUrl: String) {
        if (!file.exists()) {
            Log.w(Streams.CLOUD, "enqueue skipped, missing file: $file")
            return
        }
        val rowId = repository.queueUpload(tripId, file.name, file.length())
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .setInputData(
                Data.Builder()
                    .putLong(UploadWorker.KEY_JOB_ROW, rowId)
                    .putString(UploadWorker.KEY_FILE, file.absolutePath)
                    .putLong(UploadWorker.KEY_TRIP, tripId)
                    .putString(UploadWorker.KEY_BASE_URL, baseUrl)
                    .putString(UploadWorker.KEY_JOB_TYPE, jobType.name)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("upload_$rowId", ExistingWorkPolicy.KEEP, request)
        Log.i(Streams.CLOUD, "Enqueued upload row=$rowId trip=$tripId ${file.name} -> $baseUrl")
    }
}
