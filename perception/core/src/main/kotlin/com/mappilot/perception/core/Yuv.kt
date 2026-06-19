package com.mappilot.perception.core

/**
 * NV21 → packed RGB conversion (pure). NV21 is Y plane (w·h) followed by
 * interleaved V,U (w·h/2). Output is 3 bytes/pixel RGB. Unit-tested on synthetic
 * planes so the colour math is verified off-device.
 */
object Yuv {
    fun nv21ToRgb(nv21: ByteArray, width: Int, height: Int): ByteArray {
        val rgb = ByteArray(width * height * 3)
        val frameSize = width * height
        var rgbIdx = 0
        for (j in 0 until height) {
            var uvp = frameSize + (j shr 1) * width
            var u = 0
            var v = 0
            for (i in 0 until width) {
                val y = (nv21[j * width + i].toInt() and 0xFF) - 16
                val yy = if (y < 0) 0 else y
                if (i and 1 == 0) {
                    v = (nv21[uvp++].toInt() and 0xFF) - 128
                    u = (nv21[uvp++].toInt() and 0xFF) - 128
                }
                val y1192 = 1192 * yy
                var r = (y1192 + 1634 * v)
                var g = (y1192 - 833 * v - 400 * u)
                var b = (y1192 + 2066 * u)
                r = r.coerceIn(0, 262143)
                g = g.coerceIn(0, 262143)
                b = b.coerceIn(0, 262143)
                rgb[rgbIdx++] = (r shr 10).toByte()
                rgb[rgbIdx++] = (g shr 10).toByte()
                rgb[rgbIdx++] = (b shr 10).toByte()
            }
        }
        return rgb
    }
}
