package com.mappilot.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mappilot.app.R
import com.mappilot.core.common.bus.EventBus
import com.mappilot.core.common.log.Log
import com.mappilot.core.common.log.Streams
import com.mappilot.core.common.time.TimeSource
import com.mappilot.core.model.MapPilotEvent
import com.mappilot.core.model.RecordingState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground service that will own a recording session (Phase 2). It already
 * establishes the lifecycle, typed-FGS promotion, and RecordingState event
 * emission so later phases plug capture into a stable host. Recording is owned
 * here and is never gated by perception or upload.
 */
@AndroidEntryPoint
class RecordingService : Service() {

    @Inject lateinit var eventBus: EventBus
    @Inject lateinit var timeSource: TimeSource

    override fun onCreate() {
        super.onCreate()
        createChannel()
        emitState(RecordingState.IDLE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
            else -> Log.w(Streams.SERVICE, "Unknown action: ${intent?.action}")
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        emitState(RecordingState.STARTING)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.recording_notification_title))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        // Phase 2 wires the capture + MCAP pipeline here.
        emitState(RecordingState.RECORDING)
        Log.i(Streams.SERVICE, "Recording session started")
    }

    private fun stopRecording() {
        emitState(RecordingState.STOPPING)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        emitState(RecordingState.IDLE)
        Log.i(Streams.SERVICE, "Recording session stopped")
    }

    private fun emitState(state: RecordingState) {
        eventBus.emit(
            MapPilotEvent.RecordingStateChanged(timeSource.elapsedRealtimeNanos(), state),
        )
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.recording_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.recording_channel_desc) }
        manager.createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.mappilot.app.action.START_RECORDING"
        const val ACTION_STOP = "com.mappilot.app.action.STOP_RECORDING"
        private const val CHANNEL_ID = "mappilot_recording"
        private const val NOTIFICATION_ID = 1001
    }
}
