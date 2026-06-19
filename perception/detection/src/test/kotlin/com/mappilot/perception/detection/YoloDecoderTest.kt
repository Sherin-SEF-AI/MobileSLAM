package com.mappilot.perception.detection

import com.google.common.truth.Truth.assertThat
import com.mappilot.core.model.AssetClass
import com.mappilot.core.model.BoundingBox
import org.junit.Test

class YoloDecoderTest {

    private val anchors = 8400

    /** Build a channel-major output with one anchor set to a box + class score. */
    private fun output(
        anchorIndex: Int,
        cx: Float, cy: Float, w: Float, h: Float,
        classIndex: Int, score: Float,
    ): FloatArray {
        val out = FloatArray(YoloDecoder.NUM_CHANNELS * anchors)
        out[anchorIndex] = cx
        out[anchors + anchorIndex] = cy
        out[2 * anchors + anchorIndex] = w
        out[3 * anchors + anchorIndex] = h
        out[(4 + classIndex) * anchors + anchorIndex] = score
        return out
    }

    @Test
    fun `decodes a single detection in input pixel space`() {
        // normalized cx=0.5, cy=0.5, w=0.2, h=0.4, class 9 (traffic light), score 0.9
        val out = output(100, 0.5f, 0.5f, 0.2f, 0.4f, classIndex = 9, score = 0.9f)
        val dets = YoloDecoder.decode(out, anchors, inputSize = 640, confThreshold = 0.35f)
        assertThat(dets).hasSize(1)
        val d = dets[0]
        assertThat(d.classIndex).isEqualTo(9)
        assertThat(d.score).isWithin(1e-6f).of(0.9f)
        // center (320,320), size (128,256) → box (256,192)-(384,448)
        assertThat(d.box.left).isWithin(0.5f).of(256f)
        assertThat(d.box.top).isWithin(0.5f).of(192f)
        assertThat(d.box.right).isWithin(0.5f).of(384f)
        assertThat(d.box.bottom).isWithin(0.5f).of(448f)
    }

    @Test
    fun `below-threshold scores are discarded`() {
        val out = output(5, 0.5f, 0.5f, 0.1f, 0.1f, classIndex = 2, score = 0.2f)
        assertThat(YoloDecoder.decode(out, anchors, confThreshold = 0.35f)).isEmpty()
    }

    @Test
    fun `nms suppresses overlapping same-class boxes`() {
        val a = YoloDecoder.RawBox(BoundingBox(0f, 0f, 100f, 100f), 0.9f, 1)
        val b = YoloDecoder.RawBox(BoundingBox(10f, 10f, 105f, 105f), 0.8f, 1) // high overlap
        val c = YoloDecoder.RawBox(BoundingBox(500f, 500f, 600f, 600f), 0.7f, 1) // disjoint
        val kept = YoloDecoder.nms(listOf(a, b, c), iouThreshold = 0.45f)
        assertThat(kept).hasSize(2)
        assertThat(kept.map { it.score }).containsExactly(0.9f, 0.7f)
    }

    @Test
    fun `nms keeps overlapping boxes of different classes`() {
        val a = YoloDecoder.RawBox(BoundingBox(0f, 0f, 100f, 100f), 0.9f, 1)
        val b = YoloDecoder.RawBox(BoundingBox(0f, 0f, 100f, 100f), 0.8f, 2)
        assertThat(YoloDecoder.nms(listOf(a, b), 0.45f)).hasSize(2)
    }

    @Test
    fun `coco mapping covers road assets and ignores non-assets`() {
        assertThat(CocoLabels.toAssetClass(9)).isEqualTo(AssetClass.TRAFFIC_LIGHT)  // traffic light
        assertThat(CocoLabels.toAssetClass(11)).isEqualTo(AssetClass.TRAFFIC_SIGN)  // stop sign
        assertThat(CocoLabels.toAssetClass(0)).isNull()  // person → not a road asset
        assertThat(CocoLabels.toAssetClass(2)).isNull()  // car → not a road asset
        assertThat(CocoLabels.name(9)).isEqualTo("traffic light")
    }
}
