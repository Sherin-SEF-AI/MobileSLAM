package com.mappilot.slam.fusion

import com.mappilot.core.model.EnuPoint
import com.mappilot.core.model.GeoPoint
import com.mappilot.core.model.Vector3
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Offline drift-corrected georeferencing of a finished session.
 *
 * The VIO trajectory is treated as a 2D polyline on the ground plane: each segment
 * contributes an odometry edge of (length, 0, turn-angle), which is locally
 * accurate and free of any camera-orientation convention. The GPS fixes enter as
 * absolute position priors. [PoseGraph2D] then pulls the polyline onto GPS while
 * preserving its local shape, distributing the accumulated VIO drift that a single
 * global similarity cannot remove.
 *
 * Heading is the travel direction, so odometry and the initial guess share one
 * frame (rotation-invariant turn angles). Planar only; planar drift dominates road
 * mapping and altitude is carried from the ENU origin.
 */
object TrajectoryRefiner {

    data class TimedGeo(val timestampNs: Long, val geo: GeoPoint, val accuracyM: Float)
    data class TimedPos(val timestampNs: Long, val position: Vector3)

    /** Drift-corrected WGS84 trajectory, one point per input pose (empty if not solvable). */
    fun refine(
        vio: List<TimedPos>,
        fixes: List<TimedGeo>,
        enuFrame: EnuFrame,
        initTransform: SimilarityTransform,
        odomInfo: Double = 50.0,
    ): List<GeoPoint> {
        if (vio.size < MIN_NODES || fixes.isEmpty()) return emptyList()

        // Ground-plane positions: ARCore Y is up, so the plane is (X, -Z) -> right-handed top-down.
        val px = DoubleArray(vio.size) { vio[it].position.x }
        val py = DoubleArray(vio.size) { -vio[it].position.z }
        val heading = DoubleArray(vio.size)
        for (i in 0 until vio.size - 1) heading[i] = atan2(py[i + 1] - py[i], px[i + 1] - px[i])
        heading[vio.size - 1] = heading[vio.size - 2]

        // Initial ENU guess from the online transform; heading from travel direction.
        val nodes = vio.map { p ->
            val e = initTransform.apply(p.position)
            PoseGraph2D.Node(e.east, e.north, 0.0)
        }.toMutableList()
        for (i in nodes.indices) {
            val a = nodes[if (i < nodes.size - 1) i else i - 1]
            val b = nodes[if (i < nodes.size - 1) i + 1 else i]
            nodes[i].theta = atan2(b.y - a.y, b.x - a.x)
        }

        // Polyline odometry: forward by the segment length, turn by the heading change.
        val odom = (0 until vio.size - 1).map { i ->
            val length = hypot(px[i + 1] - px[i], py[i + 1] - py[i])
            val dtheta = PoseGraph2D.normalizeAngle(heading[i + 1] - heading[i])
            PoseGraph2D.OdomEdge(i, dx = length, dy = 0.0, dtheta = dtheta, infoT = odomInfo, infoR = odomInfo)
        }

        // GPS priors, associated to the nearest-in-time pose.
        val gps = fixes.mapNotNull { f ->
            val idx = nearestIndex(vio, f.timestampNs) ?: return@mapNotNull null
            val e = enuFrame.toEnu(f.geo)
            val sigma = f.accuracyM.coerceAtLeast(1f).toDouble()
            PoseGraph2D.GpsPrior(idx, e.east, e.north, info = 1.0 / (sigma * sigma))
        }
        if (gps.size < 2) return emptyList() // need >=2 anchors to fix position + orientation

        val result = PoseGraph2D.optimize(nodes, odom, gps)
        val baseAlt = enuFrame.origin.altitude
        return result.nodes.map { enuFrame.toGeo(EnuPoint(it.x, it.y, 0.0)).copy(altitude = baseAlt) }
    }

    private fun nearestIndex(poses: List<TimedPos>, ts: Long): Int? {
        var best = -1; var bestDelta = Long.MAX_VALUE
        for (i in poses.indices) {
            val d = kotlin.math.abs(poses[i].timestampNs - ts)
            if (d < bestDelta) { bestDelta = d; best = i }
        }
        return if (best >= 0 && bestDelta <= MAX_ASSOC_NS) best else null
    }

    private const val MIN_NODES = 3
    private const val MAX_ASSOC_NS = 1_000_000_000L // 1 s
}
