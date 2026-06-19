package com.mappilot.recording.mcap

import com.google.common.truth.Truth.assertThat
import com.mappilot.core.model.CameraIntrinsics
import com.mappilot.core.model.Constellation
import com.mappilot.core.model.FrameMeta
import com.mappilot.core.model.GnssEpoch
import com.mappilot.core.model.GnssFix
import com.mappilot.core.model.GnssSatellite
import com.mappilot.core.model.ImuChannel
import com.mappilot.core.model.ImuSample
import com.mappilot.recording.mcap.proto.CameraFrame
import com.mappilot.recording.mcap.proto.GpsFix
import com.mappilot.recording.mcap.proto.ImuSample as ImuSampleProto
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Test

class McapWriterTest {

    private fun intrinsics() = CameraIntrinsics(
        fx = 1000.0, fy = 1000.0, cx = 960.0, cy = 540.0,
        imageWidth = 1920, imageHeight = 1080,
    )

    /** Writes a representative trip into [os]. Returns (frames, imuSamples). */
    private fun writeTrip(os: java.io.OutputStream, frames: Int = 40, imuPerFrame: Int = 7): Pair<Int, Int> {
        val w = McapTripWriter(os, library = "mappilot-test", chunkTargetBytes = 4096)
        w.start()
        w.writeCalibration("Pixel-Test", "Android 14", intrinsics(), "REALTIME", mapOf("camera" to 0L), ts = 1_000)
        var ts = 1_000_000L
        var imu = 0
        repeat(frames) { f ->
            w.writeCameraFrame(
                FrameMeta(
                    frameId = f.toLong(), timestampNs = ts, width = 1920, height = 1080,
                    exposureNs = 8_000_000, iso = 200, intrinsics = intrinsics(),
                    videoPtsNs = ts, isKeyframe = f % 10 == 0,
                ),
            )
            repeat(imuPerFrame) { i ->
                w.writeImu(ImuSample(ImuChannel.ACCEL, ts + i * 1000L, 0.1f, 9.8f, 0.2f, 3))
                imu++
            }
            if (f % 5 == 0) {
                w.writeGnss(
                    GnssEpoch(
                        timestampNs = ts,
                        fix = GnssFix(ts, 12.97, 77.59, 920.0, 1.4f, 90f, 3.5f, 5.0f, "gps"),
                        satellites = listOf(
                            GnssSatellite(1, Constellation.GPS, 42f, true, 30f, 45f),
                            GnssSatellite(9, Constellation.IRNSS, 38f, true, 120f, 60f),
                        ),
                        rawMeasurements = emptyList(),
                    ),
                )
            }
            ts += 33_000_000L
        }
        w.finish()
        return frames to imu
    }

    @Test
    fun `round-trips messages with valid index and crc`() {
        val os = ByteArrayOutputStream()
        val (frames, imu) = writeTrip(os)
        val bytes = os.toByteArray()

        // Valid magic at both ends.
        assertThat(bytes.copyOfRange(0, 8)).isEqualTo(Mcap.MAGIC)
        assertThat(bytes.copyOfRange(bytes.size - 8, bytes.size)).isEqualTo(Mcap.MAGIC)

        val r = McapReader().read(bytes)
        assertThat(r.hasFooter).isTrue()
        assertThat(r.truncated).isFalse()
        assertThat(r.allChunkCrcValid).isTrue()
        assertThat(r.chunkCount).isAtLeast(1)
        // Chunk index written for every chunk (summary present and indexed).
        assertThat(r.summaryChunkIndexCount).isEqualTo(r.chunkCount)

        // All topics present.
        val topics = r.channels.values.map { it.topic }
        assertThat(topics).containsAtLeast(
            Topics.CAMERA, Topics.IMU_ACCEL, Topics.GPS_FIX, Topics.GPS_SAT, Topics.CALIBRATION,
        )

        // Message counts match what we wrote.
        val byTopic = r.messages.groupingBy { r.channels[it.channelId]!!.topic }.eachCount()
        assertThat(byTopic[Topics.CAMERA]).isEqualTo(frames)
        assertThat(byTopic[Topics.IMU_ACCEL]).isEqualTo(imu)
    }

    @Test
    fun `messages decode as protobuf using embedded schema bytes`() {
        val os = ByteArrayOutputStream()
        writeTrip(os, frames = 10, imuPerFrame = 2)
        val r = McapReader().read(os.toByteArray())

        // Decode a camera frame and a gps fix from raw bytes.
        val camChannel = r.channels.values.first { it.topic == Topics.CAMERA }
        val camMsg = r.messages.first { it.channelId == camChannel.id }
        val frame = CameraFrame.parseFrom(camMsg.data)
        assertThat(frame.width).isEqualTo(1920)
        assertThat(frame.hasIntrinsics).isTrue()
        assertThat(frame.intrinsics.fx).isWithin(1e-6).of(1000.0)

        val fixChannel = r.channels.values.first { it.topic == Topics.GPS_FIX }
        val fixMsg = r.messages.first { it.channelId == fixChannel.id }
        val fix = GpsFix.parseFrom(fixMsg.data)
        assertThat(fix.lat).isWithin(1e-9).of(12.97)

        // Embedded schema is a non-empty FileDescriptorSet for the message type.
        val camSchema = r.schemas[camChannel.schemaId]!!
        assertThat(camSchema.encoding).isEqualTo("protobuf")
        assertThat(camSchema.name).isEqualTo("mappilot.CameraFrame")
        assertThat(camSchema.data).isNotEmpty()
    }

    @Test
    fun `imu sample protobuf preserves values`() {
        val os = ByteArrayOutputStream()
        val w = McapTripWriter(os, "t")
        w.start()
        w.writeImu(ImuSample(ImuChannel.GYRO, 5_000, 1.5f, -2.5f, 3.5f, 2))
        w.finish()
        val r = McapReader().read(os.toByteArray())
        val ch = r.channels.values.first { it.topic == Topics.IMU_GYRO }
        val msg = ImuSampleProto.parseFrom(r.messages.first { it.channelId == ch.id }.data)
        assertThat(msg.x).isEqualTo(1.5f)
        assertThat(msg.z).isEqualTo(3.5f)
        assertThat(msg.accuracy).isEqualTo(2)
    }

    @Test
    fun `writes a golden file to disk for reference-reader validation`() {
        val out = File(System.getProperty("java.io.tmpdir"), "mappilot-golden.mcap")
        out.outputStream().use { writeTrip(it, frames = 60) }
        assertThat(out.length()).isGreaterThan(1000L)
        // Path printed so the build step can validate it with the python mcap lib.
        println("GOLDEN_MCAP=${out.absolutePath}")
    }
}
