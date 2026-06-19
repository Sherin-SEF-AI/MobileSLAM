package com.mappilot.assets.extraction

import com.mappilot.core.model.AssetClass
import com.mappilot.core.model.BoundingBox
import com.mappilot.core.model.Vector3
import kotlin.math.sqrt

/**
 * Temporal dedup/track of asset detections across frames. A detection's
 * backprojected world point is associated with an existing asset of the same
 * class within [mergeDistanceM]; otherwise a new asset is created. Merging keeps
 * a running-mean world position and the max confidence, so a real object seen in
 * many frames collapses to one geolocated asset.
 *
 * Dedup is in the locally-consistent VIO/world frame; geo coordinates are derived
 * downstream from each asset's world position via the current alignment.
 */
class AssetTracker(private val mergeDistanceM: Double = 3.0) {

    data class Tracked(
        val id: Long,
        val assetClass: AssetClass,
        var world: Vector3,
        var observations: Int,
        var maxConfidence: Float,
        var depthSumM: Double,
        var lastBox: BoundingBox,
        var lastFrameId: Long,
    ) {
        val depthAvgM: Double get() = if (observations > 0) depthSumM / observations else Double.NaN
    }

    private val tracked = ArrayList<Tracked>()
    private var nextId = 1L

    val assets: List<Tracked> get() = tracked.toList()
    val count: Int get() = tracked.size

    /**
     * Fold a detection into the asset set. Returns the affected asset and whether
     * it was newly created.
     */
    fun observe(
        world: Vector3,
        assetClass: AssetClass,
        confidence: Float,
        depthM: Double,
        box: BoundingBox,
        frameId: Long,
    ): Pair<Tracked, Boolean> {
        val match = tracked
            .filter { it.assetClass == assetClass }
            .minByOrNull { distance(it.world, world) }
            ?.takeIf { distance(it.world, world) <= mergeDistanceM }

        if (match != null) {
            val n = match.observations + 1
            // Running mean of the world position.
            match.world = Vector3(
                match.world.x + (world.x - match.world.x) / n,
                match.world.y + (world.y - match.world.y) / n,
                match.world.z + (world.z - match.world.z) / n,
            )
            match.observations = n
            match.maxConfidence = maxOf(match.maxConfidence, confidence)
            match.depthSumM += depthM
            match.lastBox = box
            match.lastFrameId = frameId
            return match to false
        }

        val created = Tracked(
            id = nextId++,
            assetClass = assetClass,
            world = world,
            observations = 1,
            maxConfidence = confidence,
            depthSumM = depthM,
            lastBox = box,
            lastFrameId = frameId,
        )
        tracked.add(created)
        return created to true
    }

    private fun distance(a: Vector3, b: Vector3): Double {
        val dx = a.x - b.x; val dy = a.y - b.y; val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
