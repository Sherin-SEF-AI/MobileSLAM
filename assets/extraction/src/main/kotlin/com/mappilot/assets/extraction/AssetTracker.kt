package com.mappilot.assets.extraction

import com.mappilot.core.model.AssetClass
import com.mappilot.core.model.BoundingBox
import com.mappilot.core.model.Vector3
import kotlin.math.sqrt

/**
 * Temporal accumulation of asset detections into a labeled landmark graph. A
 * detection's backprojected world point is associated with the nearest existing
 * landmark within [mergeDistanceM] (across classes, so a single real object that a
 * frame-to-frame detector occasionally mislabels still collapses to one landmark).
 *
 * Each landmark is a Bayesian accumulation rather than a single reading:
 *  - position: online (Welford) mean plus covariance, so [Tracked.positionStdM]
 *    reports how tightly the observations agree;
 *  - class: a confidence-weighted vote, so the reported [Tracked.assetClass] is the
 *    consensus over all observations, not whatever the last frame guessed;
 *  - semantics: a majority vote over ARCore Scene Semantics labels at the detection.
 *
 * Dedup is in the locally consistent VIO/world frame; WGS84 is derived downstream
 * from each landmark's world position via the current (VPS or GPS) alignment.
 */
class AssetTracker(private val mergeDistanceM: Double = 3.0) {

    class Tracked(
        val id: Long,
        var world: Vector3,
        var observations: Int,
        var maxConfidence: Float,
        var depthSumM: Double,
        var lastBox: BoundingBox,
        var lastFrameId: Long,
    ) {
        // Welford covariance accumulators (sum of squared deviations per axis).
        internal var m2x: Double = 0.0
        internal var m2y: Double = 0.0
        internal var m2z: Double = 0.0

        /** class -> summed detection confidence (the vote). */
        internal val classVotes = HashMap<AssetClass, Float>()

        /** ARCore Scene Semantics label -> observation count. */
        internal val semanticVotes = HashMap<String, Int>()

        val depthAvgM: Double get() = if (observations > 0) depthSumM / observations else Double.NaN

        /** Consensus class over all observations (confidence-weighted vote). */
        val assetClass: AssetClass
            get() = classVotes.maxByOrNull { it.value }?.key ?: AssetClass.UNKNOWN

        /** Isotropic 1-sigma positional uncertainty (m); NaN until 2+ observations. */
        val positionStdM: Double
            get() = if (observations > 1) sqrt((m2x + m2y + m2z) / (3.0 * (observations - 1))) else Double.NaN

        /** Majority Scene Semantics label, or null if none were sampled. */
        val semanticLabel: String? get() = semanticVotes.maxByOrNull { it.value }?.key
    }

    private val tracked = ArrayList<Tracked>()
    private var nextId = 1L

    val assets: List<Tracked> get() = tracked.toList()
    val count: Int get() = tracked.size

    /**
     * Fold a detection into the landmark set. Returns the affected landmark and
     * whether it was newly created. [semanticLabel] is the optional ARCore Scene
     * Semantics label at the detection centre.
     */
    fun observe(
        world: Vector3,
        assetClass: AssetClass,
        confidence: Float,
        depthM: Double,
        box: BoundingBox,
        frameId: Long,
        semanticLabel: String? = null,
    ): Pair<Tracked, Boolean> {
        val match = tracked
            .minByOrNull { distance(it.world, world) }
            ?.takeIf { distance(it.world, world) <= mergeDistanceM }

        if (match != null) {
            val n = match.observations + 1
            // Welford online mean + covariance per axis.
            val dx = world.x - match.world.x
            val dy = world.y - match.world.y
            val dz = world.z - match.world.z
            val mx = match.world.x + dx / n
            val my = match.world.y + dy / n
            val mz = match.world.z + dz / n
            match.m2x += dx * (world.x - mx)
            match.m2y += dy * (world.y - my)
            match.m2z += dz * (world.z - mz)
            match.world = Vector3(mx, my, mz)
            match.observations = n
            match.maxConfidence = maxOf(match.maxConfidence, confidence)
            match.depthSumM += depthM
            match.lastBox = box
            match.lastFrameId = frameId
            match.classVotes[assetClass] = (match.classVotes[assetClass] ?: 0f) + confidence
            semanticLabel?.let { match.semanticVotes[it] = (match.semanticVotes[it] ?: 0) + 1 }
            return match to false
        }

        val created = Tracked(
            id = nextId++,
            world = world,
            observations = 1,
            maxConfidence = confidence,
            depthSumM = depthM,
            lastBox = box,
            lastFrameId = frameId,
        )
        created.classVotes[assetClass] = confidence
        semanticLabel?.let { created.semanticVotes[it] = 1 }
        tracked.add(created)
        return created to true
    }

    private fun distance(a: Vector3, b: Vector3): Double {
        val dx = a.x - b.x; val dy = a.y - b.y; val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
