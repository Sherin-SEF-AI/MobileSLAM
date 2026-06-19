package com.mappilot.core.common.log

import timber.log.Timber

/**
 * Structured logging facade. Every hot-path stream logs under a stable tag so
 * device logs can be filtered per subsystem (`adb logcat -s MapPilot.camera`).
 */
object Log {
    fun tag(stream: String): Timber.Tree = Timber.tag("MapPilot.$stream")

    fun d(stream: String, message: String) = Timber.tag("MapPilot.$stream").d(message)
    fun i(stream: String, message: String) = Timber.tag("MapPilot.$stream").i(message)
    fun w(stream: String, message: String) = Timber.tag("MapPilot.$stream").w(message)
    fun e(stream: String, t: Throwable?, message: String) =
        Timber.tag("MapPilot.$stream").e(t, message)
}

/** Well-known stream tags. Kept central so logs and MCAP topics stay aligned. */
object Streams {
    const val CAMERA = "camera"
    const val IMU = "imu"
    const val GNSS = "gnss"
    const val SYNC = "sync"
    const val RECORDING = "recording"
    const val SLAM = "slam"
    const val FUSION = "fusion"
    const val PERCEPTION = "perception"
    const val ASSETS = "assets"
    const val DB = "db"
    const val EXPORT = "export"
    const val CLOUD = "cloud"
    const val SERVICE = "service"
}
