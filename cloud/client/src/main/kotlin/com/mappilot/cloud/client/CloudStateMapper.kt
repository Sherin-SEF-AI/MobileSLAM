package com.mappilot.cloud.client

/** Maps server upload/job states to the app's [CloudState] (contract §3). Pure. */
object CloudStateMapper {

    fun fromJobState(serverState: String): CloudState = when (serverState.uppercase()) {
        "QUEUED" -> CloudState.PROCESSING   // upload done; job queued for the pipeline
        "PROCESSING" -> CloudState.PROCESSING
        "READY" -> CloudState.READY
        "FAILED" -> CloudState.FAILED
        else -> CloudState.PROCESSING
    }

    fun fromUpload(receivedChunks: Int, totalChunks: Int): CloudState = when {
        totalChunks > 0 && receivedChunks >= totalChunks -> CloudState.PROCESSING
        receivedChunks > 0 -> CloudState.UPLOADING
        else -> CloudState.QUEUED
    }
}
