package com.mappilot.app

import android.app.Application
import com.mappilot.app.recording.RecordingController
import dagger.hilt.android.HiltAndroidApp
import kotlin.concurrent.thread
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class MapPilotApplication : Application() {

    @Inject lateinit var recordingController: RecordingController

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.tag("MapPilot.app").i("MapPilot %s started", BuildConfig.VERSION_NAME)

        // Finalize any MCAP left unsealed by a crash; off the main thread.
        thread(name = "trip-recovery", isDaemon = true) {
            val outcomes = recordingController.recoverInterruptedTrips()
            val recovered = outcomes.count { it is com.mappilot.recording.mcap.McapRecoverer.Outcome.Recovered }
            if (recovered > 0) {
                Timber.tag("MapPilot.recording").i("Recovered %d interrupted MCAP segment(s)", recovered)
            }
        }
    }
}
