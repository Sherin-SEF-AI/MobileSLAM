package com.mappilot.assets.extraction

import com.mappilot.core.model.Landmark
import kotlin.math.floor

/**
 * Voxel-grid downsampling for the sparse point cloud: collapse all points that
 * fall in the same [voxelSizeM] cube to a single representative (the highest
 * confidence one). Cuts storage and render cost and suppresses the dense bloom of
 * near-duplicate points, while preserving one real sample per occupied cell.
 *
 * Pure and order-independent (keyed by integer cell), so it is unit tested.
 */
object VoxelGrid {
    fun downsample(points: List<Landmark>, voxelSizeM: Double): List<Landmark> {
        if (voxelSizeM <= 0.0 || points.size <= 1) return points
        val best = HashMap<Long, Landmark>(points.size)
        for (p in points) {
            val key = cellKey(p.position.x, p.position.y, p.position.z, voxelSizeM)
            val cur = best[key]
            if (cur == null || p.confidence > cur.confidence) best[key] = p
        }
        return best.values.toList()
    }

    /** Pack a 3D integer cell into one Long (21 bits per axis, signed). */
    private fun cellKey(x: Double, y: Double, z: Double, v: Double): Long {
        val cx = floor(x / v).toLong() and MASK
        val cy = floor(y / v).toLong() and MASK
        val cz = floor(z / v).toLong() and MASK
        return (cx shl 42) or (cy shl 21) or cz
    }

    private const val MASK = 0x1FFFFFL // 21 bits
}
