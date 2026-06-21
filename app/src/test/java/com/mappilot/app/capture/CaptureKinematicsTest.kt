package com.mappilot.app.capture

import com.google.common.truth.Truth.assertThat
import com.mappilot.core.model.Quaternion
import com.mappilot.core.model.Vector3
import org.junit.Test

class CaptureKinematicsTest {

    private val identity = Quaternion(0.0, 0.0, 0.0, 1.0)

    @Test
    fun `speed is distance over time`() {
        val s = CaptureKinematics.speedMps(Vector3(0.0, 0.0, 0.0), Vector3(2.0, 0.0, 0.0), dtNs = 1_000_000_000)
        assertThat(s).isWithin(1e-9).of(2.0) // 2 m in 1 s
    }

    @Test
    fun `angle between identical orientations is zero`() {
        assertThat(CaptureKinematics.angleDeg(identity, identity)).isWithin(1e-6).of(0.0)
    }

    @Test
    fun `90 degree yaw is measured as 90 degrees`() {
        val yaw90 = Quaternion(0.0, 0.70710678, 0.0, 0.70710678) // 90 deg about Y
        assertThat(CaptureKinematics.angleDeg(identity, yaw90)).isWithin(1e-3).of(90.0)
    }

    @Test
    fun `rotate-in-place is flagged when turning fast while nearly stationary`() {
        val st = CaptureKinematics.evaluate(speedMps = 0.1, rotationDegPerS = 40.0)
        assertThat(st.rotateInPlace).isTrue()
        assertThat(st.warning).isNotNull()
    }

    @Test
    fun `too-fast is flagged above the speed threshold`() {
        val st = CaptureKinematics.evaluate(speedMps = 8.0, rotationDegPerS = 5.0)
        assertThat(st.tooFast).isTrue()
        assertThat(st.rotateInPlace).isFalse()
    }

    @Test
    fun `good capture has no warning`() {
        val st = CaptureKinematics.evaluate(speedMps = 1.2, rotationDegPerS = 10.0)
        assertThat(st.warning).isNull()
        assertThat(st.tooFast).isFalse()
        assertThat(st.rotateInPlace).isFalse()
    }
}
