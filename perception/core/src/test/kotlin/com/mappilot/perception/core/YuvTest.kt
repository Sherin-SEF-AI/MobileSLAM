package com.mappilot.perception.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class YuvTest {

    @Test
    fun `output size is 3 bytes per pixel`() {
        val w = 4; val h = 2
        val nv21 = ByteArray(w * h + w * h / 2)
        val rgb = Yuv.nv21ToRgb(nv21, w, h)
        assertThat(rgb.size).isEqualTo(w * h * 3)
    }

    @Test
    fun `reuses the provided output buffer when large enough`() {
        val w = 4; val h = 2
        val nv21 = ByteArray(w * h + w * h / 2)
        val reusable = ByteArray(w * h * 3)
        val out = Yuv.nv21ToRgb(nv21, w, h, reusable)
        // Same instance returned — no per-frame allocation on the hot path.
        assertThat(out).isSameInstanceAs(reusable)
    }

    @Test
    fun `allocates a fresh buffer when the supplied one is too small`() {
        val w = 4; val h = 2
        val nv21 = ByteArray(w * h + w * h / 2)
        val tooSmall = ByteArray(3)
        val out = Yuv.nv21ToRgb(nv21, w, h, tooSmall)
        assertThat(out).isNotSameInstanceAs(tooSmall)
        assertThat(out.size).isEqualTo(w * h * 3)
    }

    @Test
    fun `mid-grey luma with neutral chroma yields grey rgb`() {
        val w = 2; val h = 2
        val nv21 = ByteArray(w * h + w * h / 2)
        // Y = 128 everywhere, U=V=128 (neutral)
        for (i in 0 until w * h) nv21[i] = 128.toByte()
        nv21[w * h] = 128.toByte()      // V
        nv21[w * h + 1] = 128.toByte()  // U
        val rgb = Yuv.nv21ToRgb(nv21, w, h)
        // Neutral chroma → r≈g≈b, mid-grey
        val r = rgb[0].toInt() and 0xFF
        val g = rgb[1].toInt() and 0xFF
        val b = rgb[2].toInt() and 0xFF
        assertThat(Math.abs(r - g)).isAtMost(2)
        assertThat(Math.abs(g - b)).isAtMost(2)
        assertThat(r).isIn(120..140)
    }
}
