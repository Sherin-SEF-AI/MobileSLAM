package com.mappilot.sensors.camera

import android.media.Image

/** A CPU-accessible analysis frame for perception (NV21), with its sync timestamp. */
data class CameraImage(
    val frameId: Long,
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val nv21: ByteArray,
)

/**
 * Converts a YUV_420_888 [Image] to NV21 (Y plane + interleaved V,U), honouring
 * row and pixel strides. Copies into a freshly-sized array so the [Image] can be
 * closed immediately and never blocks the camera callback.
 */
internal fun Image.toNv21(): ByteArray {
    val w = width
    val h = height
    val out = ByteArray(w * h * 3 / 2)
    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]

    // Y
    var pos = 0
    val yBuf = yPlane.buffer
    val yRowStride = yPlane.rowStride
    val yPixStride = yPlane.pixelStride
    for (row in 0 until h) {
        var col = row * yRowStride
        if (yPixStride == 1) {
            yBuf.position(col)
            yBuf.get(out, pos, w)
            pos += w
        } else {
            for (c in 0 until w) { out[pos++] = yBuf.get(col); col += yPixStride }
        }
    }

    // Interleaved VU (NV21).
    val uBuf = uPlane.buffer
    val vBuf = vPlane.buffer
    val uvRowStride = uPlane.rowStride
    val uvPixStride = uPlane.pixelStride
    val chromaH = h / 2
    val chromaW = w / 2
    for (row in 0 until chromaH) {
        var uCol = row * uvRowStride
        var vCol = row * uvRowStride
        for (c in 0 until chromaW) {
            out[pos++] = vBuf.get(vCol)
            out[pos++] = uBuf.get(uCol)
            uCol += uvPixStride
            vCol += uvPixStride
        }
    }
    return out
}
