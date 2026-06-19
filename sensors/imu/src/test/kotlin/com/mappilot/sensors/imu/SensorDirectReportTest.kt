package com.mappilot.sensors.imu

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SensorDirectReportTest {

    private fun buildRecord(
        token: Int,
        type: Int,
        counter: Long,
        timestampNs: Long,
        x: Float,
        y: Float,
        z: Float,
    ): ByteBuffer {
        val buf = ByteBuffer.allocate(SensorDirectReport.RECORD_SIZE).order(ByteOrder.nativeOrder())
        buf.putInt(0, SensorDirectReport.RECORD_SIZE)
        buf.putInt(4, token)
        buf.putInt(8, type)
        buf.putInt(12, counter.toInt())
        buf.putLong(16, timestampNs)
        buf.putFloat(24, x)
        buf.putFloat(28, y)
        buf.putFloat(32, z)
        return buf
    }

    @Test
    fun `decodes a well-formed record`() {
        val buf = buildRecord(
            token = 7, type = 1, counter = 42, timestampNs = 123_456_789L,
            x = 0.1f, y = -9.8f, z = 0.3f,
        )
        val r = SensorDirectReport.decode(buf, 0)!!
        assertThat(r.size).isEqualTo(104)
        assertThat(r.reportToken).isEqualTo(7)
        assertThat(r.sensorType).isEqualTo(1)
        assertThat(r.atomicCounter).isEqualTo(42)
        assertThat(r.timestampNs).isEqualTo(123_456_789L)
        assertThat(r.x).isWithin(1e-6f).of(0.1f)
        assertThat(r.y).isWithin(1e-6f).of(-9.8f)
        assertThat(r.z).isWithin(1e-6f).of(0.3f)
    }

    @Test
    fun `unwritten slot (counter 0) decodes to null, not a fake sample`() {
        val buf = buildRecord(0, 0, counter = 0, timestampNs = 0, x = 0f, y = 0f, z = 0f)
        assertThat(SensorDirectReport.decode(buf, 0)).isNull()
    }

    @Test
    fun `high atomic counter is treated as unsigned`() {
        val buf = buildRecord(1, 1, counter = 0xFFFF_FFFFL, timestampNs = 1L, x = 0f, y = 0f, z = 0f)
        val r = SensorDirectReport.decode(buf, 0)!!
        assertThat(r.atomicCounter).isEqualTo(0xFFFF_FFFFL)
    }

    @Test
    fun `record count fits buffer`() {
        assertThat(SensorDirectReport.recordCount(104 * 10)).isEqualTo(10)
        assertThat(SensorDirectReport.recordCount(103)).isEqualTo(0)
    }
}
