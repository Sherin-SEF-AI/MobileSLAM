package com.mappilot.slam.fusion

import com.google.common.truth.Truth.assertThat
import com.mappilot.core.common.bus.SharedFlowEventBus
import com.mappilot.core.model.EnuPoint
import com.mappilot.core.model.GeoPoint
import com.mappilot.core.model.Pose
import com.mappilot.core.model.Quaternion
import com.mappilot.core.model.TrackingFailureReason
import com.mappilot.core.model.TrackingState
import com.mappilot.core.model.Vector3
import org.junit.Test

/**
 * Regression for the on-device "map never appears" bug. ARCore's render loop runs in
 * LATEST_CAMERA_IMAGE (non-blocking) mode and re-emits the same camera frame's pose many
 * times per real frame, so VIO poses arrived at ~575 Hz (measured on a Galaxy A17). A
 * pose ring sized by a fixed count (600) then held only ~1 second of history, and GPS
 * fixes — delivered a second or more after their timestamp — could no longer find the
 * pose nearest their (older) timestamp. No correspondences accumulated, so VIO->ENU
 * alignment was never even attempted and the trajectory stayed empty.
 *
 * The ring is now pruned by TIME (20 s), so a late fix still pairs as long as its
 * timestamp is recent enough, regardless of pose rate. This test floods the ring far past
 * the old 600-entry cap, then delivers the fixes late with their original timestamps.
 */
class GnssVioFusionPoseRingTest {

    @Test
    fun `late GPS fixes still align after a high-rate pose flood evicts a count-based ring`() {
        val f = GnssVioFusion(SharedFlowEventBus())
        val origin = GeoPoint(12.97, 77.59, 900.0)
        val frame = EnuFrame(origin)
        val rng = kotlin.random.Random(7)
        val dt = 2_000_000L // 2 ms between poses (~500 Hz, like the duplicate-frame flood)
        val base = 1_000_000_000L

        // An L-shaped walk over the first 30 poses: the turn gives the similarity solve
        // the geometry it needs. These are the poses the GPS fixes will correspond to.
        val anchors = ArrayList<Pose>()
        val lpath = ArrayList<Vector3>()
        for (i in 0 until 15) lpath.add(Vector3(i.toDouble(), 0.0, 0.0))
        for (i in 1..15) lpath.add(Vector3(14.0, i.toDouble(), 0.0))
        for ((i, p) in lpath.withIndex()) {
            val pose = Pose(base + i * dt, p, Quaternion(0.0, 0.0, 0.0, 1.0), TrackingState.TRACKING, TrackingFailureReason.NONE, 1f)
            f.onPose(pose)
            anchors.add(pose)
        }

        // Flood: 2000 more poses (~4 s) — well past the old 600-entry ring, so a count-based
        // ring would evict every anchor above, but all stay inside the 20 s time window.
        for (j in 0 until 2000) {
            val t = base + (lpath.size + j) * dt
            f.onPose(Pose(t, Vector3(14.0, 15.0 + j, 0.0), Quaternion(0.0, 0.0, 0.0, 1.0), TrackingState.TRACKING, TrackingFailureReason.NONE, 1f))
        }

        // GPS fixes arrive LATE, carrying the anchors' original (now old) timestamps.
        for (a in anchors) {
            val nx = a.position.x + (rng.nextDouble() - 0.5) * 12.0
            val ny = a.position.y + (rng.nextDouble() - 0.5) * 12.0
            f.onGnssFix(frame.toGeo(EnuPoint(nx, ny, a.position.z)), a.timestampNs, hAccuracyM = 8f)
        }

        assertThat(f.state.value.aligned).isTrue()
        assertThat(f.state.value.rmsErrorM).isLessThan(18.0)
    }

    /**
     * On-device, the VIO pose for a given instant reaches the fusion a few hundred ms
     * AFTER the GPS fix for the same instant (camera/bus latency). The fix must not be
     * thrown away when its pose isn't present yet — it is deferred and paired once the
     * pose arrives. Here every fix is delivered strictly before its matching pose.
     */
    @Test
    fun `aligns when each GPS fix arrives before its matching VIO pose`() {
        val f = GnssVioFusion(SharedFlowEventBus())
        val origin = GeoPoint(12.97, 77.59, 900.0)
        val frame = EnuFrame(origin)
        val rng = kotlin.random.Random(11)

        val lpath = ArrayList<Vector3>()
        for (i in 0 until 15) lpath.add(Vector3(i.toDouble(), 0.0, 0.0))
        for (i in 1..15) lpath.add(Vector3(14.0, i.toDouble(), 0.0))

        var t = 1_000_000_000L
        for (p in lpath) {
            t += 33_000_000L // ~30 Hz
            // Fix first: no pose for this instant exists yet, so it must be deferred...
            val nx = p.x + (rng.nextDouble() - 0.5) * 12.0
            val ny = p.y + (rng.nextDouble() - 0.5) * 12.0
            f.onGnssFix(frame.toGeo(EnuPoint(nx, ny, p.z)), t, hAccuracyM = 8f)
            // ...then the matching pose arrives and the deferred fix pairs.
            f.onPose(Pose(t, p, Quaternion(0.0, 0.0, 0.0, 1.0), TrackingState.TRACKING, TrackingFailureReason.NONE, 1f))
        }

        assertThat(f.state.value.aligned).isTrue()
        assertThat(f.state.value.rmsErrorM).isLessThan(18.0)
    }
}
