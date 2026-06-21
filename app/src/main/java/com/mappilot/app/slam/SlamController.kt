package com.mappilot.app.slam

import com.mappilot.core.common.bus.EventBus
import com.mappilot.core.common.log.Log
import com.mappilot.core.common.log.Streams
import com.mappilot.core.model.GeoPoint
import com.mappilot.core.model.Landmark
import com.mappilot.core.model.MapPilotEvent
import com.mappilot.slam.core.SlamConfig
import com.mappilot.slam.core.SlamEngine
import com.mappilot.slam.core.SlamState
import com.mappilot.slam.fusion.FusionState
import com.mappilot.slam.fusion.GnssVioFusion
import com.mappilot.geo.trajectory.TrajectoryBuilder
import com.mappilot.geo.trajectory.TrajectoryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates SLAM + georeferencing for a session: runs the [SlamEngine],
 * routes VIO poses and GNSS fixes into [GnssVioFusion] (Umeyama VIO→ENU), and
 * builds the georeferenced [TrajectoryBuilder] from the resulting ENU poses.
 * All coupling is via the event bus (ADR 0004).
 */
@Singleton
class SlamController @Inject constructor(
    private val slamEngine: SlamEngine,
    private val fusion: GnssVioFusion,
    private val eventBus: EventBus,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile var trajectory = TrajectoryBuilder(); private set

    /** Most recent georeferenced landmark cloud (capped), for 3D + persistence. */
    private val landmarks = LinkedHashMap<Long, Landmark>()

    val slamState get() = slamEngine.state
    val fusionState get() = fusion.state

    @Synchronized
    fun currentLandmarks(): List<Landmark> = landmarks.values.toList()

    fun start(config: SlamConfig = SlamConfig()) {
        if (slamEngine.isRunning) return
        fusion.reset()
        trajectory = TrajectoryBuilder()
        synchronized(this) { landmarks.clear() }
        subscribe()
        slamEngine.start(config)
        Log.i(Streams.SLAM, "SlamController started")
    }

    private fun subscribe() {
        eventBus.events
            .onEach { event ->
                when (event) {
                    is MapPilotEvent.PoseUpdate -> fusion.onPose(event.pose)
                    is MapPilotEvent.GeospatialUpdate ->
                        fusion.onGeospatial(event.correspondences, event.horizontalAccuracyM, event.headingAccuracyDeg)
                    is MapPilotEvent.GnssFixReceived -> event.epoch.fix?.let { fix ->
                        fusion.onGnssFix(
                            GeoPoint(fix.latitude, fix.longitude, fix.altitude),
                            fix.timestampNs,
                            fix.hAccuracyM,
                        )
                    }
                    is MapPilotEvent.LandmarksUpdated -> accumulateLandmarks(event.landmarks)
                    is MapPilotEvent.EnuPoseUpdate -> {
                        val geo = fusion.originFrame()?.toGeo(event.pose.enu) ?: return@onEach
                        trajectory.add(
                            TrajectoryPoint(
                                timestampNs = event.timestampNs,
                                geo = geo,
                                east = event.pose.enu.east,
                                north = event.pose.enu.north,
                                up = event.pose.enu.up,
                            ),
                        )
                    }
                    else -> Unit
                }
            }
            .launchIn(scope)
    }

    @Synchronized
    private fun accumulateLandmarks(updates: List<Landmark>) {
        val transform = fusion.currentTransform()
        val enuFrame = fusion.originFrame()
        for (l in updates) {
            val geo = if (transform != null && enuFrame != null) enuFrame.toGeo(transform.apply(l.position)) else null
            landmarks[l.id] = l.copy(geo = geo)
            if (landmarks.size > MAX_LANDMARKS) {
                val oldest = landmarks.keys.first()
                landmarks.remove(oldest)
            }
        }
    }

    fun stop() {
        slamEngine.stop()
        scope.coroutineContext.cancelChildren()
        Log.i(Streams.SLAM, "SlamController stopped: trajectory ${trajectory.size} pts, ${"%.1f".format(trajectory.lengthM())}m")
    }

    fun currentState(): Pair<SlamState, FusionState> = slamState.value to fusionState.value

    private companion object {
        const val MAX_LANDMARKS = 50_000
    }
}
