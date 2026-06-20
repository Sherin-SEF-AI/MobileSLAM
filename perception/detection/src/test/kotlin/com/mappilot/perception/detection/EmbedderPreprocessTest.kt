package com.mappilot.perception.detection

import com.google.common.truth.Truth.assertThat
import com.mappilot.core.model.BoundingBox
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class EmbedderPreprocessTest {

    @Test
    fun `fills size x size x 3 floats sampling the crop`() {
        val w = 4; val h = 4
        // Uniform image: every pixel (10,20,30) → any sampling yields the same.
        val rgb = ByteArray(w * h * 3)
        for (i in 0 until w * h) {
            rgb[i * 3] = 10; rgb[i * 3 + 1] = 20; rgb[i * 3 + 2] = 30
        }
        val size = 2
        val buf = ByteBuffer.allocateDirect(size * size * 3 * 4).order(ByteOrder.nativeOrder())
        EmbedderPreprocess.fillInput(rgb, w, h, BoundingBox(0f, 0f, w.toFloat(), h.toFloat()), size, buf)

        buf.rewind()
        val floats = FloatArray(size * size * 3) { buf.float }
        assertThat(floats.size).isEqualTo(12)
        // Values are raw [0,255] (no normalization) in R,G,B order.
        for (px in 0 until size * size) {
            assertThat(floats[px * 3]).isEqualTo(10f)
            assertThat(floats[px * 3 + 1]).isEqualTo(20f)
            assertThat(floats[px * 3 + 2]).isEqualTo(30f)
        }
    }

    @Test
    fun `degenerate box is clamped without overflow`() {
        val w = 8; val h = 8
        val rgb = ByteArray(w * h * 3) { 5 }
        val buf = ByteBuffer.allocateDirect(2 * 2 * 3 * 4).order(ByteOrder.nativeOrder())
        // right<=left / bottom<=top: must clamp to a valid 1px+ crop, not crash.
        EmbedderPreprocess.fillInput(rgb, w, h, BoundingBox(5f, 5f, 1f, 1f), 2, buf)
        buf.rewind()
        assertThat(buf.float).isEqualTo(5f)
    }
}
