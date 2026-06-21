package com.mappilot.assets.extraction

import com.google.common.truth.Truth.assertThat
import com.mappilot.core.model.AssetClass
import com.mappilot.core.model.BoundingBox
import com.mappilot.core.model.Vector3
import org.junit.Test

class AssetTrackerTest {

    private val box = BoundingBox(0f, 0f, 10f, 10f)

    private fun AssetTracker.obs(x: Double, cls: AssetClass, conf: Float = 0.8f, frame: Long = 0) =
        observe(Vector3(x, 0.0, 0.0), cls, conf, depthM = 5.0, box = box, frameId = frame)

    @Test
    fun `nearby same-class detections merge into one asset`() {
        val t = AssetTracker(mergeDistanceM = 3.0)
        val (a, newA) = t.obs(0.0, AssetClass.TRAFFIC_LIGHT)
        val (b, newB) = t.obs(1.0, AssetClass.TRAFFIC_LIGHT) // 1 m away
        assertThat(newA).isTrue()
        assertThat(newB).isFalse()
        assertThat(a.id).isEqualTo(b.id)
        assertThat(t.count).isEqualTo(1)
        assertThat(b.observations).isEqualTo(2)
        assertThat(b.world.x).isWithin(1e-9).of(0.5) // running mean of 0 and 1
    }

    @Test
    fun `far same-class detections create separate assets`() {
        val t = AssetTracker(mergeDistanceM = 3.0)
        t.obs(0.0, AssetClass.TRAFFIC_LIGHT)
        t.obs(10.0, AssetClass.TRAFFIC_LIGHT)
        assertThat(t.count).isEqualTo(2)
    }

    @Test
    fun `co-located cross-class detections merge and the consensus class wins by vote`() {
        val t = AssetTracker(mergeDistanceM = 3.0)
        // Two POLE observations + one mislabeled TRAFFIC_SIGN at the same spot.
        t.obs(0.0, AssetClass.POLE, conf = 0.7f)
        t.obs(0.2, AssetClass.TRAFFIC_SIGN, conf = 0.6f)
        val (asset, _) = t.obs(0.1, AssetClass.POLE, conf = 0.8f)
        assertThat(t.count).isEqualTo(1)
        assertThat(asset.observations).isEqualTo(3)
        // POLE vote 1.5 > TRAFFIC_SIGN vote 0.6 -> consensus is POLE.
        assertThat(asset.assetClass).isEqualTo(AssetClass.POLE)
    }

    @Test
    fun `merge keeps max confidence and averages depth`() {
        val t = AssetTracker(mergeDistanceM = 3.0)
        t.observe(Vector3.ZERO, AssetClass.POLE, 0.6f, depthM = 4.0, box = box, frameId = 0)
        val (asset, _) = t.observe(Vector3(0.5, 0.0, 0.0), AssetClass.POLE, 0.9f, depthM = 6.0, box = box, frameId = 1)
        assertThat(asset.maxConfidence).isEqualTo(0.9f)
        assertThat(asset.depthAvgM).isWithin(1e-9).of(5.0)
        assertThat(asset.lastFrameId).isEqualTo(1)
    }

    @Test
    fun `positionStdM reflects observation spread`() {
        val t = AssetTracker(mergeDistanceM = 5.0)
        val (a1, _) = t.obs(0.0, AssetClass.POLE)
        assertThat(a1.positionStdM.isNaN()).isTrue() // undefined for a single observation
        t.obs(0.1, AssetClass.POLE)
        val (a3, _) = t.obs(-0.1, AssetClass.POLE)
        assertThat(a3.observations).isEqualTo(3)
        assertThat(a3.positionStdM).isGreaterThan(0.0)
        assertThat(a3.positionStdM).isLessThan(0.2)
    }

    @Test
    fun `semantic label is the majority vote`() {
        val t = AssetTracker(mergeDistanceM = 3.0)
        t.observe(Vector3.ZERO, AssetClass.POLE, 0.8f, 5.0, box, 0, semanticLabel = "STRUCTURE")
        t.observe(Vector3(0.1, 0.0, 0.0), AssetClass.POLE, 0.8f, 5.0, box, 1, semanticLabel = "STRUCTURE")
        val (a, _) = t.observe(Vector3(-0.1, 0.0, 0.0), AssetClass.POLE, 0.8f, 5.0, box, 2, semanticLabel = "VEHICLE")
        assertThat(a.semanticLabel).isEqualTo("STRUCTURE")
    }
}
