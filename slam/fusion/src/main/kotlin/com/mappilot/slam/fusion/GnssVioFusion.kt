package com.mappilot.slam.fusion

import com.mappilot.core.common.bus.EventBus
import com.mappilot.core.common.log.Log
import com.mappilot.core.common.log.Streams
import com.mappilot.core.model.EnuPose
import com.mappilot.core.model.GeoPoint
import com.mappilot.core.model.MapPilotEvent
import com.mappilot.core.model.Pose
import com.mappilot.core.model.TrackingState
import com.mappilot.core.model.Vector3
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/** Live georeferencing status for the HUD/analytics. */
data class FusionState(
    val aligned: Boolean = false,
    val correspondences: Int = 0,
    val rmsErrorM: Double = Double.NaN,
    val scale: Double = Double.NaN,
    val transformId: Long = 0,
    /** True when the active transform came from ARCore Geospatial (VPS), not GPS. */
    val vps: Boolean = false,
)

/**
 * Online VIO→ENU georeferencing. Buffers (VIO pose, GNSS-ENU) correspondences as
 * fixes arrive, re-solves the [Umeyama] similarity as the buffer grows, and
 * republishes every incoming VIO pose as an [EnuPose].
 *
 * Wiring is via the bus (ADR 0004): subscribe externally to PoseUpdate and
 * GnssFixReceived and feed them in. The transform is only published once it is
 * sufficiently constrained and its RMS residual is acceptable — otherwise the
 * state stays `aligned = false` rather than emitting a bogus georeference.
 */
@Singleton
class GnssVioFusion @Inject constructor(
    private val eventBus: EventBus,
) {
    private val _state = MutableStateFlow(FusionState())
    val state: StateFlow<FusionState> = _state.asStateFlow()

    private var enuFrame: EnuFrame? = null
    private val vioPoints = ArrayList<Vector3>()
    private val enuPoints = ArrayList<com.mappilot.core.model.EnuPoint>()

    @Volatile private var transform: SimilarityTransform? = null
    private var transformId: Long = 0

    /** Once VPS provides a transform, GPS-based alignment yields to it (more accurate). */
    @Volatile private var vpsActive = false

    /** Recent VIO poses keyed by timestamp, to pair with a GNSS fix near its time. */
    private val recentPoses = ConcurrentHashMap<Long, Pose>()
    private val poseRing = ArrayDeque<Long>()

    @Synchronized
    fun reset() {
        enuFrame = null
        vioPoints.clear()
        enuPoints.clear()
        transform = null
        transformId = 0
        vpsActive = false
        recentPoses.clear()
        poseRing.clear()
        _state.value = FusionState()
    }

    /** Feed a VIO pose. Republishes it as ENU when a transform exists. */
    fun onPose(pose: Pose) {
        if (pose.trackingState == TrackingState.TRACKING) {
            recentPoses[pose.timestampNs] = pose
            poseRing.addLast(pose.timestampNs)
            while (poseRing.size > POSE_RING_CAPACITY) {
                recentPoses.remove(poseRing.removeFirst())
            }
        }
        val t = transform ?: return
        val enu = t.apply(pose.position)
        eventBus.emit(
            MapPilotEvent.EnuPoseUpdate(
                pose.timestampNs,
                EnuPose(pose.timestampNs, enu, pose.orientation, transformId),
            ),
        )
    }

    /**
     * Feed Earth-anchored (VPS) correspondences for one keyframe: exact VIO<->WGS84
     * pairs from ARCore Geospatial. A single keyframe's set solves the VIO->ENU
     * transform directly (no GPS-vs-VIO accumulation, no drift), and it takes
     * precedence over GPS while VPS is active. This is what makes vehicle-speed and
     * multi-session capture work where the GPS+VIO path cannot.
     */
    @Synchronized
    fun onGeospatial(
        correspondences: List<com.mappilot.core.model.GeoCorrespondence>,
        hAccuracyM: Float,
        headingAccuracyDeg: Float,
    ) {
        val valid = correspondences.filter {
            it.vio.x.isFinite() && it.vio.y.isFinite() && it.vio.z.isFinite() &&
                it.geo.latitude.isFinite() && it.geo.longitude.isFinite()
        }
        if (valid.size < Umeyama.MIN_CORRESPONDENCES) return
        val frame = enuFrame ?: EnuFrame(valid.first().geo).also { enuFrame = it }
        val vio = valid.map { it.vio }
        val enu = valid.map { frame.toEnu(it.geo) }
        val sol = Umeyama.solve(vio, enu) ?: return
        if (!sol.rmsErrorM.isFinite() || sol.rmsErrorM > MAX_RMS_ERROR_M) return
        vpsActive = true
        transform = sol
        transformId++
        _state.value = FusionState(
            aligned = true, correspondences = sol.correspondences, rmsErrorM = sol.rmsErrorM,
            scale = sol.scale, transformId = transformId, vps = true,
        )
    }

    /** Feed a GNSS fix. Adds a correspondence (if a near-in-time VIO pose exists) and re-solves. */
    @Synchronized
    fun onGnssFix(fix: GeoPoint, fixTimestampNs: Long, hAccuracyM: Float) {
        if (vpsActive) return // VPS transform is live and more accurate; ignore GPS
        if (hAccuracyM > MAX_FIX_ACCURACY_M && hAccuracyM >= 0) return // too noisy to anchor on
        val frame = enuFrame ?: EnuFrame(fix).also { enuFrame = it }

        val pose = nearestPose(fixTimestampNs) ?: return
        // Never anchor on a non-finite VIO pose (ARCore can emit NaN during poor
        // tracking) — it would poison the Umeyama solution and every georeferenced
        // point derived from it.
        if (!pose.position.x.isFinite() || !pose.position.y.isFinite() || !pose.position.z.isFinite()) return
        vioPoints.add(pose.position)
        enuPoints.add(frame.toEnu(fix))

        if (vioPoints.size < Umeyama.MIN_CORRESPONDENCES) return
        val sol = Umeyama.solve(vioPoints, enuPoints) ?: return
        // Reject non-finite solutions too: a NaN rms slips past `> MAX` (NaN
        // comparisons are always false), which would otherwise store a NaN transform.
        if (!sol.rmsErrorM.isFinite() || sol.rmsErrorM > MAX_RMS_ERROR_M) {
            Log.w(Streams.FUSION, "Alignment rejected: RMS ${"%.2f".format(sol.rmsErrorM)}m > ${MAX_RMS_ERROR_M}m")
            return
        }
        transform = sol
        transformId++
        _state.value = FusionState(
            aligned = true,
            correspondences = sol.correspondences,
            rmsErrorM = sol.rmsErrorM,
            scale = sol.scale,
            transformId = transformId,
        )
        Log.i(
            Streams.FUSION,
            "VIO→ENU aligned: n=${sol.correspondences} scale=${"%.3f".format(sol.scale)} rms=${"%.2f".format(sol.rmsErrorM)}m",
        )
    }

    /** Current transform's ENU origin, for converting ENU back to geo. */
    fun originFrame(): EnuFrame? = enuFrame

    /** The current VIO→ENU similarity transform, or null until aligned. */
    fun currentTransform(): SimilarityTransform? = transform

    private fun nearestPose(tsNs: Long): Pose? {
        recentPoses[tsNs]?.let { return it }
        var best: Pose? = null
        var bestDelta = Long.MAX_VALUE
        for ((ts, p) in recentPoses) {
            val d = abs(ts - tsNs)
            if (d < bestDelta) { bestDelta = d; best = p }
        }
        return if (bestDelta <= MAX_PAIR_DELTA_NS) best else null
    }

    private companion object {
        const val POSE_RING_CAPACITY = 600
        const val MAX_PAIR_DELTA_NS = 100_000_000L // 100 ms
        const val MAX_FIX_ACCURACY_M = 20f
        const val MAX_RMS_ERROR_M = 5.0
    }
}
