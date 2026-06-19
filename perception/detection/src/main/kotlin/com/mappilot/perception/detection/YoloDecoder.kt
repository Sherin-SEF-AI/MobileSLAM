package com.mappilot.perception.detection

import com.mappilot.core.model.BoundingBox
import kotlin.math.max
import kotlin.math.min

/**
 * Decodes the YOLO11 detect head output and applies non-max suppression. Pure
 * and unit-tested — the model run is device-only, but the tensor→boxes logic is
 * verified here.
 *
 * Output tensor is `[1, 84, 8400]` channel-major: for each of 8400 anchors,
 * channels 0..3 are box (cx,cy,w,h) normalized to [0,1] of the model input, and
 * channels 4..83 are per-class scores (already activated). Boxes are returned in
 * model-input pixel space (normalized × [inputSize]).
 */
object YoloDecoder {
    const val NUM_CLASSES = 80
    const val NUM_CHANNELS = 84
    const val NUM_ANCHORS = 8400

    data class RawBox(val box: BoundingBox, val score: Float, val classIndex: Int)

    /**
     * @param output flat array of size [NUM_CHANNELS]*[anchors], channel-major.
     * @param inputSize model input edge (e.g. 640); boxes are scaled into it.
     */
    fun decode(
        output: FloatArray,
        anchors: Int = NUM_ANCHORS,
        inputSize: Int = 640,
        confThreshold: Float = 0.35f,
        iouThreshold: Float = 0.45f,
    ): List<RawBox> {
        require(output.size >= NUM_CHANNELS * anchors) { "output too small: ${output.size}" }
        val candidates = ArrayList<RawBox>()

        for (a in 0 until anchors) {
            // Best class for this anchor.
            var bestScore = 0f
            var bestClass = -1
            for (c in 0 until NUM_CLASSES) {
                val s = output[(4 + c) * anchors + a]
                if (s > bestScore) { bestScore = s; bestClass = c }
            }
            if (bestScore < confThreshold || bestClass < 0) continue

            val cx = output[a] * inputSize
            val cy = output[anchors + a] * inputSize
            val w = output[2 * anchors + a] * inputSize
            val h = output[3 * anchors + a] * inputSize
            candidates.add(
                RawBox(
                    BoundingBox(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2),
                    bestScore,
                    bestClass,
                ),
            )
        }
        return nms(candidates, iouThreshold)
    }

    /** Greedy per-class non-max suppression. */
    fun nms(boxes: List<RawBox>, iouThreshold: Float): List<RawBox> {
        val byClass = boxes.groupBy { it.classIndex }
        val kept = ArrayList<RawBox>()
        for ((_, group) in byClass) {
            val sorted = group.sortedByDescending { it.score }.toMutableList()
            while (sorted.isNotEmpty()) {
                val best = sorted.removeAt(0)
                kept.add(best)
                sorted.removeAll { iou(best.box, it.box) > iouThreshold }
            }
        }
        return kept.sortedByDescending { it.score }
    }

    fun iou(a: BoundingBox, b: BoundingBox): Float {
        val ix = max(0f, min(a.right, b.right) - max(a.left, b.left))
        val iy = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        val inter = ix * iy
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        val union = areaA + areaB - inter
        return if (union <= 0f) 0f else inter / union
    }
}
