package com.mappilot.perception.detection

import com.google.common.truth.Truth.assertThat
import com.mappilot.core.model.BoundingBox
import org.junit.Test

class LetterboxTest {

    @Test
    fun `landscape frame letterboxes with vertical padding`() {
        // 1920x1080 into 640 → scale = 640/1920 = 0.3333, newH=360, padY=140
        val info = Letterbox.infoFor(1920, 1080, 640)
        assertThat(info.scale).isWithin(1e-6).of(640.0 / 1920.0)
        assertThat(info.padX).isWithin(1e-6).of(0.0)
        assertThat(info.padY).isWithin(1e-6).of(140.0)
    }

    @Test
    fun `inverse maps a model-space box back to original pixels`() {
        val info = Letterbox.infoFor(1920, 1080, 640)
        // A box centered in the padded image at (320,320) size (64,64) → (288,288)-(352,352)
        val orig = info.toOriginal(BoundingBox(288f, 288f, 352f, 352f))
        // x: (288-0)/0.3333 = 864 ; (352)/0.3333 = 1056
        assertThat(orig.left).isWithin(1f).of(864f)
        assertThat(orig.right).isWithin(1f).of(1056f)
        // y: (288-140)/0.3333 = 444 ; (352-140)/0.3333 = 636
        assertThat(orig.top).isWithin(1f).of(444f)
        assertThat(orig.bottom).isWithin(1f).of(636f)
    }

    @Test
    fun `inverse clamps to frame bounds`() {
        val info = Letterbox.infoFor(640, 640, 640) // scale 1, no pad
        val orig = info.toOriginal(BoundingBox(-50f, -50f, 9999f, 9999f))
        assertThat(orig.left).isEqualTo(0f)
        assertThat(orig.top).isEqualTo(0f)
        assertThat(orig.right).isEqualTo(640f)
        assertThat(orig.bottom).isEqualTo(640f)
    }
}
