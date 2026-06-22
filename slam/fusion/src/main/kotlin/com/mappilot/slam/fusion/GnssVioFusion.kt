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

    private var gnssFixCount = 0

    /** Recent VIO poses keyed by timestamp, to pair with a GNSS fix near its time. */
    private val recentPoses = ConcurrentHashMap<Long, Pose>()
    private val poseRing = ArrayDeque<Long>()

    /**
     * GNSS fixes that arrived before their near-in-time VIO pose. Camera/bus latency can
     * deliver a frame to the fusion a few hundred ms after the GPS fix for the same
     * instant, so the matching pose isn't in [recentPoses] yet when the fix is processed.
     * These are held briefly and paired retroactively in [onPose]; without this a real fix
     * is discarded forever and a session can fail to georeference even though its recorded
     * poses and fixes pair perfectly offline.
     */
    private data class PendingFix(val ts: Long, val fix: GeoPoint)
    private val pendingFixes = ArrayDeque<PendingFix>()

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
        pendingFixes.clear()
        gnssFixCount = 0
        _state.value = FusionState()
    }

    /** Feed a VIO pose. Republishes it as ENU when a transform exists. */
    @Synchronized
    fun onPose(pose: Pose) {
        if (pose.trackingState == TrackingState.TRACKING) {
            recentPoses[pose.timestampNs] = pose
            poseRing.addLast(pose.timestampNs)
            // Prune by TIME, not a fixed count: ARCore emits poses at hundreds of Hz on
            // some devices, so a count-based ring collapses to a fraction of a second of
            // history and laggy GPS fixes (delivered 1s+ after their timestamp) can never
            // find their matching pose. Keep a generous time window; a hard count cap is
            // only a memory backstop for pathological rates.
            val cutoff = pose.timestampNs - POSE_RETENTION_NS
            while (poseRing.isNotEmpty() &&
                (poseRing.first() < cutoff || poseRing.size > POSE_RING_CAPACITY)
            ) {
                recentPoses.remove(poseRing.removeFirst())
            }
            // A new pose may be the partner a deferred GPS fix was waiting for.
            if (pendingFixes.isNotEmpty()) resolvePendingFixes()
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
        gnssFixCount++
        val diag = gnssFixCount % 5 == 1 // sample ~every 5th fix to avoid spam
        if (vpsActive) return // VPS transform is live and more accurate; ignore GPS
        if (hAccuracyM > MAX_FIX_ACCURACY_M && hAccuracyM >= 0) {
            if (diag) Log.w(Streams.FUSION, "fix rejected: acc=${hAccuracyM}m > ${MAX_FIX_ACCURACY_M}m")
            return
        }
        enuFrame = enuFrame ?: EnuFrame(fix)

        val pose = nearestPose(fixTimestampNs)
        if (pose == null) {
            // The VIO pose nearest this fix may simply not have arrived at the fusion yet
            // (camera/bus latency delivers the frame after the GPS fix for the same
            // instant). Defer it; onPose pairs it once the matching pose shows up.
            pendingFixes.addLast(PendingFix(fixTimestampNs, fix))
            while (pendingFixes.size > MAX_PENDING_FIXES) pendingFixes.removeFirst()
            if (diag) Log.w(Streams.FUSION, "fix deferred: no VIO pose yet near ts=$fixTimestampNs (pending=${pendingFixes.size}, poses=${recentPoses.size})")
            return
        }
        if (addCorrespondence(pose, fix)) {
            if (diag) Log.i(Streams.FUSION, "fix paired (acc=${hAccuracyM}m, corr=${vioPoints.size}, poses=${recentPoses.size})")
            solveAndPublish(diag)
        }
    }

    /**
     * Add one VIO<->GPS correspondence. Returns false (and adds nothing) for a non-finite
     * VIO pose — ARCore can emit NaN during poor tracking, which would poison the Umeyama
     * solution and every georeferenced point derived from it.
     */
    private fun addCorrespondence(pose: Pose, fix: GeoPoint): Boolean {
        if (!pose.position.x.isFinite() || !pose.position.y.isFinite() || !pose.position.z.isFinite()) return false
        val frame = enuFrame ?: return false
        vioPoints.add(pose.position)
        enuPoints.add(frame.toEnu(fix))
        // Slide a window over recent correspondences: a single global similarity can't
        // fit VIO that has drifted over a long session, so keep only the recent ones.
        // The transform then tracks the locally-consistent VIO near the current position.
        while (vioPoints.size > MAX_CORRESPONDENCES) {
            vioPoints.removeAt(0); enuPoints.removeAt(0)
        }
        return true
    }

    /** Re-solve VIO->ENU from the accumulated correspondences and publish if accepted. */
    private fun solveAndPublish(diag: Boolean) {
        if (vioPoints.size < Umeyama.MIN_CORRESPONDENCES) return
        val sol = Umeyama.solve(vioPoints, enuPoints)
        if (sol == null) {
            if (diag) Log.w(Streams.FUSION, "Umeyama returned null (degenerate, corr=${vioPoints.size})")
            return
        }
        // Reject non-finite solutions too: a NaN rms slips past `> MAX` (NaN
        // comparisons are always false), which would otherwise store a NaN transform.
        if (!sol.rmsErrorM.isFinite() || sol.rmsErrorM > MAX_RMS_ERROR_M) {
            Log.w(Streams.FUSION, "Alignment rejected: RMS ${"%.2f".format(sol.rmsErrorM)}m > ${MAX_RMS_ERROR_M}m")
            return
        }
        // VIO and ENU are both metric, so the similarity scale must be ~1. A wildly off
        // scale means the device has barely moved while GPS jittered (e.g. sitting still
        // indoors): the solve is fitting GPS noise, not real motion. Reject it — and keep
        // waiting until there is enough travel to anchor on. Prevents a garbage map.
        if (sol.scale < MIN_SCALE || sol.scale > MAX_SCALE) {
            Log.w(Streams.FUSION, "Alignment rejected: scale ${"%.2f".format(sol.scale)} out of [$MIN_SCALE,$MAX_SCALE] (too little motion vs GPS noise)")
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

    /** Pair any deferred GPS fixes whose VIO pose has now arrived; drop ones gone stale. */
    private fun resolvePendingFixes() {
        if (vpsActive) { pendingFixes.clear(); return }
        val oldestPose = poseRing.firstOrNull()
        var added = false
        val it = pendingFixes.iterator()
        while (it.hasNext()) {
            val pf = it.next()
            val pose = nearestPose(pf.ts)
            if (pose != null) {
                if (addCorrespondence(pose, pf.fix)) added = true
                it.remove()
            } else if (oldestPose != null && pf.ts < oldestPose - MAX_PAIR_DELTA_NS) {
                // Its pose window has fully passed without a match (e.g. tracking was lost
                // around that instant); stop waiting.
                it.remove()
            }
        }
        if (added) solveAndPublish(false)
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
        const val POSE_RETENTION_NS = 20_000_000_000L // keep 20s of pose history (tolerates GPS delivery lag)
        const val POSE_RING_CAPACITY = 16_000 // memory backstop (~28s at 575Hz, far more at typical rates)
        const val MAX_PAIR_DELTA_NS = 100_000_000L // 100 ms
        const val MAX_PENDING_FIXES = 64 // deferred fixes awaiting their VIO pose
        const val MIN_SCALE = 0.5 // VIO<->ENU are both metric; reject implausible similarity scale
        const val MAX_SCALE = 2.0
        // Consumer-grade GNSS: accept fixes up to ~35 m accuracy, and accept an
        // alignment whose residual is within the phone-GPS noise floor (a 5 m gate is
        // below that floor, so it rejected every real fix). Tighter accuracy comes
        // from the VPS path, not from rejecting usable GPS here.
        const val MAX_FIX_ACCURACY_M = 35f
        const val MAX_RMS_ERROR_M = 18.0
        /** Sliding window of recent VIO<->ENU correspondences fed to Umeyama. */
        const val MAX_CORRESPONDENCES = 600
    }
}
