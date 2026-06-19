package com.mappilot.perception.detection

import com.mappilot.core.model.BoundingBox
import com.mappilot.perception.core.InferenceFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Aspect-preserving letterbox into the square model input, plus the inverse
 * mapping from model-input pixel boxes back to original-frame pixels. The
 * inverse mapping ([Info.toOriginal]) is pure and unit-tested.
 */
object Letterbox {

    data class Info(
        val scale: Double,
        val padX: Double,
        val padY: Double,
        val origWidth: Int,
        val origHeight: Int,
    ) {
        /** Map a box in model-input (e.g. 640) space back to original-frame pixels. */
        fun toOriginal(box: BoundingBox): BoundingBox {
            fun mapX(x: Float) = (((x - padX) / scale).coerceIn(0.0, origWidth.toDouble())).toFloat()
            fun mapY(y: Float) = (((y - padY) / scale).coerceIn(0.0, origHeight.toDouble())).toFloat()
            return BoundingBox(mapX(box.left), mapY(box.top), mapX(box.right), mapY(box.bottom))
        }
    }

    fun infoFor(origWidth: Int, origHeight: Int, inputSize: Int): Info {
        val scale = minOf(inputSize.toDouble() / origWidth, inputSize.toDouble() / origHeight)
        val newW = origWidth * scale
        val newH = origHeight * scale
        return Info(scale, (inputSize - newW) / 2.0, (inputSize - newH) / 2.0, origWidth, origHeight)
    }

    /**
     * Build the float32 NHWC input buffer (normalized 0..1) and the [Info] needed
     * to invert detections. Nearest-neighbour sampling; pad with YOLO's 114/255 grey.
     */
    fun toModelInput(frame: InferenceFrame, inputSize: Int): Pair<ByteBuffer, Info> {
        val info = infoFor(frame.width, frame.height, inputSize)
        val buffer = ByteBuffer.allocateDirect(inputSize * inputSize * 3 * 4).order(ByteOrder.nativeOrder())
        val pad = 114f / 255f
        for (y in 0 until inputSize) {
            val srcYf = (y - info.padY) / info.scale
            for (x in 0 until inputSize) {
                val srcXf = (x - info.padX) / info.scale
                val sx = srcXf.toInt(); val sy = srcYf.toInt()
                if (sx in 0 until frame.width && sy in 0 until frame.height) {
                    val idx = (sy * frame.width + sx) * 3
                    buffer.putFloat((frame.rgb[idx].toInt() and 0xFF) / 255f)
                    buffer.putFloat((frame.rgb[idx + 1].toInt() and 0xFF) / 255f)
                    buffer.putFloat((frame.rgb[idx + 2].toInt() and 0xFF) / 255f)
                } else {
                    buffer.putFloat(pad); buffer.putFloat(pad); buffer.putFloat(pad)
                }
            }
        }
        buffer.rewind()
        return buffer to info
    }
}
