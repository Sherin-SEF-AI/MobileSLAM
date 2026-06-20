package com.mappilot.perception.detection

import com.mappilot.core.model.BoundingBox
import java.nio.ByteBuffer

/**
 * Pure crop+resize for the image embedder: extracts the [box] region from a packed
 * RGB frame and nearest-neighbour resizes it into a square [size]×[size] NHWC float
 * buffer with pixel values in [0,255] (MobileNetV3 does its own rescaling). Unit
 * tested so the sampling math is verifiable off-device.
 */
object EmbedderPreprocess {
    fun fillInput(rgb: ByteArray, width: Int, height: Int, box: BoundingBox, size: Int, buf: ByteBuffer) {
        buf.clear()
        val left = box.left.toInt().coerceIn(0, (width - 1).coerceAtLeast(0))
        val top = box.top.toInt().coerceIn(0, (height - 1).coerceAtLeast(0))
        val right = box.right.toInt().coerceIn(left + 1, width)
        val bottom = box.bottom.toInt().coerceIn(top + 1, height)
        val cw = right - left
        val ch = bottom - top
        for (y in 0 until size) {
            val sy = (top + y * ch / size).coerceIn(0, height - 1)
            for (x in 0 until size) {
                val sx = (left + x * cw / size).coerceIn(0, width - 1)
                val idx = (sy * width + sx) * 3
                buf.putFloat((rgb[idx].toInt() and 0xFF).toFloat())
                buf.putFloat((rgb[idx + 1].toInt() and 0xFF).toFloat())
                buf.putFloat((rgb[idx + 2].toInt() and 0xFF).toFloat())
            }
        }
        buf.rewind()
    }
}
