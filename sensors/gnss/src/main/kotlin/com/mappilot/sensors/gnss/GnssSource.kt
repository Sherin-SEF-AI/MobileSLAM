package com.mappilot.sensors.gnss

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import com.mappilot.core.common.bus.EventBus
import com.mappilot.core.common.capture.CaptureSource
import com.mappilot.core.common.log.Log
import com.mappilot.core.common.log.Streams
import com.mappilot.core.common.time.TimeSource
import com.mappilot.core.model.GnssEpoch
import com.mappilot.core.model.GnssFix
import com.mappilot.core.model.GnssRawMeasurement
import com.mappilot.core.model.GnssSatellite
import com.mappilot.core.model.MapPilotEvent
import com.mappilot.core.model.StreamIds
import com.mappilot.core.model.TimestampSource
import com.mappilot.core.timesync.SyncEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GNSS capture: fused/raw [Location] fixes, per-satellite [GnssStatus] (all six
 * constellations incl. NavIC/IRNSS), and raw [GnssMeasurementsEvent]. The three
 * callbacks arrive independently; the most recent satellite view and raw
 * measurements are folded into each emitted [GnssEpoch].
 *
 * Location fix timestamps come from [Location.getElapsedRealtimeNanos], already
 * in the unified base; status/raw callbacks are stamped with the unified clock
 * on arrival. If location permission is missing the source reports UNAVAILABLE
 * and stays stopped — it never emits a fabricated position.
 */
@Singleton
class GnssSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncEngine: SyncEngine,
    private val eventBus: EventBus,
    private val timeSource: TimeSource,
) : CaptureSource {

    override val name: String = "gnss"

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Volatile
    override var isRunning: Boolean = false
        private set

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile private var lastSatellites: List<GnssSatellite> = emptyList()
    @Volatile private var lastRaw: List<GnssRawMeasurement> = emptyList()

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @Synchronized
    override fun start() {
        if (isRunning) return
        if (!hasLocationPermission()) {
            Log.w(Streams.GNSS, "GNSS UNAVAILABLE: ACCESS_FINE_LOCATION not granted")
            return
        }
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Log.w(Streams.GNSS, "GNSS provider disabled by user/system")
        }

        val ht = HandlerThread("gnss").also { it.start() }
        val h = Handler(ht.looper)

        syncEngine.registerStream(StreamIds.GNSS_FIX, TimestampSource.REALTIME, FIX_RATE_HZ)

        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MIN_TIME_MS,
                MIN_DISTANCE_M,
                locationListener,
                ht.looper,
            )
            locationManager.registerGnssStatusCallback(statusCallback, h)
            locationManager.registerGnssMeasurementsCallback(measurementsCallback, h)
        } catch (se: SecurityException) {
            Log.e(Streams.GNSS, se, "GNSS registration denied")
            ht.quitSafely()
            return
        }

        thread = ht
        handler = h
        isRunning = true
        Log.i(Streams.GNSS, "GNSS started")
    }

    @Synchronized
    override fun stop() {
        if (!isRunning) return
        isRunning = false
        runCatching { locationManager.removeUpdates(locationListener) }
        runCatching { locationManager.unregisterGnssStatusCallback(statusCallback) }
        runCatching { locationManager.unregisterGnssMeasurementsCallback(measurementsCallback) }
        thread?.quitSafely()
        thread = null
        handler = null
        Log.i(Streams.GNSS, "GNSS stopped")
    }

    private val locationListener = LocationListener { location ->
        val tsRaw = location.elapsedRealtimeNanos
        val ts = syncEngine.recordSample(StreamIds.GNSS_FIX, tsRaw)
        val fix = GnssFix(
            timestampNs = ts,
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = if (location.hasAltitude()) location.altitude else Double.NaN,
            speedMps = if (location.hasSpeed()) location.speed else Float.NaN,
            bearingDeg = if (location.hasBearing()) location.bearing else Float.NaN,
            hAccuracyM = if (location.hasAccuracy()) location.accuracy else -1f,
            vAccuracyM = if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters else -1f,
            provider = location.provider ?: LocationManager.GPS_PROVIDER,
        )
        emitEpoch(ts, fix)
    }

    private val statusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val sats = ArrayList<GnssSatellite>(status.satelliteCount)
            for (i in 0 until status.satelliteCount) {
                sats.add(
                    GnssSatellite(
                        svid = status.getSvid(i),
                        constellation = status.getConstellationType(i).toConstellation(),
                        cn0DbHz = status.getCn0DbHz(i),
                        usedInFix = status.usedInFix(i),
                        azimuthDeg = status.getAzimuthDegrees(i),
                        elevationDeg = status.getElevationDegrees(i),
                    ),
                )
            }
            lastSatellites = sats
            // Emit a fix-less epoch so the HUD can show satellite health pre-fix.
            emitEpoch(timeSource.elapsedRealtimeNanos(), null)
        }
    }

    private val measurementsCallback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
            lastRaw = event.measurements.map { m ->
                GnssRawMeasurement(
                    svid = m.svid,
                    constellation = m.constellationType.toConstellation(),
                    pseudorangeRateMps = m.pseudorangeRateMetersPerSecond,
                    accumulatedDeltaRangeM = m.accumulatedDeltaRangeMeters,
                    cn0DbHz = m.cn0DbHz,
                    carrierFrequencyHz = if (m.hasCarrierFrequencyHz()) m.carrierFrequencyHz else Float.NaN,
                    state = m.state,
                )
            }
        }
    }

    private fun emitEpoch(timestampNs: Long, fix: GnssFix?) {
        val epoch = GnssEpoch(
            timestampNs = timestampNs,
            fix = fix,
            satellites = lastSatellites,
            rawMeasurements = lastRaw,
        )
        eventBus.emit(MapPilotEvent.GnssFixReceived(timestampNs, epoch))
    }

    private companion object {
        const val MIN_TIME_MS = 0L      // request the fastest fixes the provider gives
        const val MIN_DISTANCE_M = 0f
        const val FIX_RATE_HZ = 1.0
    }
}
