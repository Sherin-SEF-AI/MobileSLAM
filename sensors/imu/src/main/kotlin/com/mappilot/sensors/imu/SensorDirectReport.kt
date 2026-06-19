package com.mappilot.sensors.imu

import java.nio.ByteBuffer

/**
 * Decoder for the shared-memory sensor event records produced by a
 * [android.hardware.SensorDirectChannel]. The on-wire layout is the fixed
 * 104-byte record defined by the Android sensors HAL:
 *
 * ```
 * offset  type        field
 *   0     int32       size (== 104)
 *   4     int32       sensor report token
 *   8     int32       sensor type
 *  12     uint32      atomic counter (monotonic; 0 => slot not yet written)
 *  16     int64       timestamp (ns, elapsedRealtimeNanos base)
 *  24     float32[16] data (x,y,z,...)
 *  88     int32[4]    reserved
 * ```
 *
 * Pure and side-effect-free so it can be unit-tested against synthetic buffers
 * — we never ship an unvalidated binary parser.
 */
internal object SensorDirectReport {
    const val RECORD_SIZE = 104
    private const val OFF_SIZE = 0
    private const val OFF_TOKEN = 4
    private const val OFF_TYPE = 8
    private const val OFF_COUNTER = 12
    private const val OFF_TIMESTAMP = 16
    private const val OFF_DATA = 24
    const val DATA_FLOATS = 16

    data class Record(
        val size: Int,
        val reportToken: Int,
        val sensorType: Int,
        val atomicCounter: Long, // unsigned 32-bit widened
        val timestampNs: Long,
        val x: Float,
        val y: Float,
        val z: Float,
    )

    /**
     * Decode the record at [recordOffset] in [buffer]. The buffer must be in
     * native byte order. Returns null if the slot has not been written yet
     * (counter == 0) — a real "no data here" signal, never a fabricated sample.
     */
    fun decode(buffer: ByteBuffer, recordOffset: Int): Record? {
        val size = buffer.getInt(recordOffset + OFF_SIZE)
        val counterRaw = buffer.getInt(recordOffset + OFF_COUNTER)
        val counter = counterRaw.toLong() and 0xFFFF_FFFFL
        if (counter == 0L) return null
        return Record(
            size = size,
            reportToken = buffer.getInt(recordOffset + OFF_TOKEN),
            sensorType = buffer.getInt(recordOffset + OFF_TYPE),
            atomicCounter = counter,
            timestampNs = buffer.getLong(recordOffset + OFF_TIMESTAMP),
            x = buffer.getFloat(recordOffset + OFF_DATA),
            y = buffer.getFloat(recordOffset + OFF_DATA + 4),
            z = buffer.getFloat(recordOffset + OFF_DATA + 8),
        )
    }

    /** Number of whole records that fit in a buffer of [bytes] bytes. */
    fun recordCount(bytes: Int): Int = bytes / RECORD_SIZE
}
