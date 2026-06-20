package com.mappilot.perception.core

import com.mappilot.core.common.result.MapPilotResult
import com.mappilot.core.model.BoundingBox
import com.mappilot.core.model.Detection

/**
 * A single RGB frame handed to perception. Pixels are tightly-packed RGB (or the
 * model-native layout the [Detector] expects); [width]/[height] are the source
 * frame dimensions so detections can be mapped back to original pixels.
 */
data class InferenceFrame(
    val frameId: Long,
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val rgb: ByteArray,
)

/** Dense depth in metres for a frame; [widthxheight], row-major. */
data class DepthMap(
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val depthMeters: FloatArray,
    val source: DepthSource,
) {
    /** Bilinear-free nearest depth at normalized (u,v) in [0,1]; NaN if invalid. */
    fun depthAtNormalized(u: Float, v: Float): Float {
        if (width <= 0 || height <= 0) return Float.NaN
        val x = (u * width).toInt().coerceIn(0, width - 1)
        val y = (v * height).toInt().coerceIn(0, height - 1)
        val d = depthMeters[y * width + x]
        return if (d > 0f && d.isFinite()) d else Float.NaN
    }
}

enum class DepthSource { ARCORE, DEPTH_ANYTHING, NONE }

/**
 * On-device object detector. Loading and inference can fail or be unavailable on
 * a device — those surface as [MapPilotResult] arms, never a fabricated result.
 */
interface Detector {
    /** Load the model + delegate. Idempotent. */
    fun load(): MapPilotResult<Unit>

    /** Run detection on one frame. Returns real model detections (possibly empty). */
    fun detect(frame: InferenceFrame): MapPilotResult<List<Detection>>

    fun close()

    /** Which acceleration delegate is actually active (NNAPI/GPU/CPU/unavailable). */
    val activeDelegate: String
}

/** On-device monocular depth estimator (fallback when ARCore depth is absent). */
interface DepthEstimator {
    fun load(): MapPilotResult<Unit>
    fun estimate(frame: InferenceFrame): MapPilotResult<DepthMap>
    fun close()
}

/**
 * On-device image embedder. Produces a fixed-length, L2-normalized feature vector
 * for an image crop, enabling visual-similarity (semantic) search over assets.
 * When no model is bundled, [load] returns [MapPilotResult.Unavailable] and
 * [embed] returns null — embeddings are never fabricated.
 */
interface Embedder {
    fun load(): MapPilotResult<Unit>

    /** Embedding dimension, or 0 until a model is loaded. */
    val dim: Int

    /** True once a real model is loaded and ready. */
    val available: Boolean

    /**
     * L2-normalized embedding of the [box] crop (in [frame] original-frame pixels),
     * or null if the embedder is unavailable / the crop is degenerate.
     */
    fun embed(frame: InferenceFrame, box: BoundingBox): FloatArray?

    fun close()
}
