package com.mappilot.app.capture

import android.view.Surface
import com.mappilot.core.common.log.Log
import com.mappilot.core.common.log.Streams
import com.mappilot.sensors.camera.CameraCaptureSource
import com.mappilot.sensors.gnss.GnssSource
import com.mappilot.sensors.imu.ImuSensorSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts and stops the three capture sources as one unit for live preview
 * (Phase 1). In Phase 2 the foreground RecordingService owns this lifecycle for
 * a recording session; here it backs the Capture screen's live HUD.
 *
 * Each source independently surfaces UNAVAILABLE if its capability/permission is
 * missing — the hub does not mask a missing sensor.
 */
@Singleton
class SensorHub @Inject constructor(
    val imu: ImuSensorSource,
    val gnss: GnssSource,
    val camera: CameraCaptureSource,
) {
    val isRunning: Boolean
        get() = imu.isRunning || gnss.isRunning || camera.isRunning

    fun setPreviewSurface(surface: Surface?) = camera.setPreviewSurface(surface)

    fun start() {
        Log.i(Streams.SERVICE, "SensorHub starting all sources")
        imu.start()
        gnss.start()
        camera.start()
    }

    fun stop() {
        Log.i(Streams.SERVICE, "SensorHub stopping all sources")
        camera.stop()
        gnss.stop()
        imu.stop()
    }
}
