package com.mappilot.cloud.client

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single configured cloud endpoint. Shared by the upload path and the
 * background [JobPollWorker] so both target the same backend. Defaults to the
 * emulator→host dev/reference server; override at runtime for a real backend.
 */
@Singleton
class CloudConfig @Inject constructor() {
    @Volatile
    var baseUrl: String = "http://10.0.2.2:8000/v1"
}
