package com.mappilot.recording.mcap

import com.google.protobuf.Descriptors
import com.mappilot.core.model.Asset
import com.mappilot.core.model.CameraIntrinsics
import com.mappilot.core.model.DeviceEvent
import com.mappilot.core.model.EnuPose
import com.mappilot.core.model.FrameMeta
import com.mappilot.core.model.GnssEpoch
import com.mappilot.core.model.ImuChannel
import com.mappilot.core.model.ImuSample
import com.mappilot.core.model.Landmark
import com.mappilot.core.model.Pose
import com.mappilot.core.model.RotationSample
import com.mappilot.recording.mcap.proto.Assets
import com.mappilot.recording.mcap.proto.Calibration
import com.mappilot.recording.mcap.proto.CameraFrame
import com.mappilot.recording.mcap.proto.Event
import com.mappilot.recording.mcap.proto.GpsFix
import com.mappilot.recording.mcap.proto.GpsRaw
import com.mappilot.recording.mcap.proto.GpsSat
import com.mappilot.recording.mcap.proto.Intrinsics
import com.mappilot.recording.mcap.proto.Landmarks
import com.mappilot.recording.mcap.proto.PoseEnu
import com.mappilot.recording.mcap.proto.Quaternion
import com.mappilot.recording.mcap.proto.RotationVector
import com.mappilot.recording.mcap.proto.Vec3
import java.io.OutputStream
import com.mappilot.recording.mcap.proto.ImuSample as ImuSampleProto
import com.mappilot.recording.mcap.proto.Pose as PoseProto

/**
 * Trip-level MCAP writer: registers every §6.2 topic with a self-describing
 * protobuf schema, then accepts domain models, converts them to protobuf, and
 * appends them to the underlying chunked [McapWriter].
 *
 * Pure JVM (full protobuf-java) so the whole pipeline is golden-tested off-device.
 * Single-threaded by contract (the recording writer thread).
 */
class McapTripWriter(
    out: OutputStream,
    library: String,
    chunkTargetBytes: Int = 1 shl 20,
) {
    private val writer = McapWriter(out, library, chunkTargetBytes)
    private val schemaIds = HashMap<String, Int>()
    private val channelIds = HashMap<String, Int>()

    val bytesWritten: Long get() = writer.bytesWritten

    fun start() {
        writer.writeHeader()
        channel(Topics.CAMERA, CameraFrame.getDescriptor())
        channel(Topics.GPS_FIX, GpsFix.getDescriptor())
        channel(Topics.GPS_RAW, GpsRaw.getDescriptor())
        channel(Topics.GPS_SAT, GpsSat.getDescriptor())
        channel(Topics.IMU_ACCEL, ImuSampleProto.getDescriptor())
        channel(Topics.IMU_GYRO, ImuSampleProto.getDescriptor())
        channel(Topics.IMU_MAG, ImuSampleProto.getDescriptor())
        channel(Topics.IMU_LINEAR_ACCEL, ImuSampleProto.getDescriptor())
        channel(Topics.IMU_GRAVITY, ImuSampleProto.getDescriptor())
        channel(Topics.IMU_ROTATION, RotationVector.getDescriptor())
        channel(Topics.POSE, PoseProto.getDescriptor())
        channel(Topics.POSE_ENU, PoseEnu.getDescriptor())
        channel(Topics.LANDMARKS, Landmarks.getDescriptor())
        channel(Topics.ASSETS, Assets.getDescriptor())
        channel(Topics.EVENTS, Event.getDescriptor())
        channel(Topics.CALIBRATION, Calibration.getDescriptor())
    }

    private fun channel(topic: String, descriptor: Descriptors.Descriptor) {
        val schemaId = schemaIds.getOrPut(descriptor.fullName) {
            writer.addSchema(
                name = descriptor.fullName,
                encoding = Topics.SCHEMA_ENCODING,
                data = ProtoSchemas.fileDescriptorSet(descriptor),
            )
        }
        channelIds[topic] = writer.addChannel(schemaId, topic, Topics.MESSAGE_ENCODING)
    }

    private fun write(topic: String, ts: Long, bytes: ByteArray) {
        val id = channelIds[topic] ?: error("unregistered topic $topic")
        writer.writeMessage(id, ts, bytes)
    }

    // --- typed writers ---

    fun writeCalibration(
        deviceModel: String,
        osVersion: String,
        intrinsics: CameraIntrinsics?,
        cameraTimestampSource: String,
        timebaseOffsetsNs: Map<String, Long>,
        ts: Long,
    ) {
        val b = Calibration.newBuilder()
            .setDeviceModel(deviceModel)
            .setOsVersion(osVersion)
            .setCameraTimestampSource(cameraTimestampSource)
            .putAllTimebaseOffsetsNs(timebaseOffsetsNs)
        if (intrinsics != null) {
            b.hasCameraIntrinsics = true
            b.cameraIntrinsics = intrinsics.toProto()
            b.imageWidth = intrinsics.imageWidth
            b.imageHeight = intrinsics.imageHeight
        }
        write(Topics.CALIBRATION, ts, b.build().toByteArray())
    }

    fun writeCameraFrame(f: FrameMeta) {
        val b = CameraFrame.newBuilder()
            .setFrameId(f.frameId)
            .setTimestampNs(f.timestampNs)
            .setWidth(f.width)
            .setHeight(f.height)
            .setExposureNs(f.exposureNs)
            .setIso(f.iso)
            .setVideoPtsNs(f.videoPtsNs)
            .setIsKeyframe(f.isKeyframe)
        if (f.intrinsics != null) {
            b.hasIntrinsics = true
            b.intrinsics = f.intrinsics!!.toProto()
        }
        write(Topics.CAMERA, f.timestampNs, b.build().toByteArray())
    }

    fun writeImu(s: ImuSample) {
        val msg = ImuSampleProto.newBuilder()
            .setTimestampNs(s.timestampNs)
            .setX(s.x).setY(s.y).setZ(s.z)
            .setAccuracy(s.accuracy)
            .build()
        write(imuTopic(s.channel), s.timestampNs, msg.toByteArray())
    }

    fun writeRotation(s: RotationSample) {
        val msg = RotationVector.newBuilder()
            .setTimestampNs(s.timestampNs)
            .setQx(s.qx).setQy(s.qy).setQz(s.qz).setQw(s.qw)
            .setAccuracy(s.accuracy)
            .build()
        write(Topics.IMU_ROTATION, s.timestampNs, msg.toByteArray())
    }

    fun writeGnss(epoch: GnssEpoch) {
        epoch.fix?.let { fix ->
            val msg = GpsFix.newBuilder()
                .setTimestampNs(fix.timestampNs)
                .setLat(fix.latitude).setLon(fix.longitude).setAlt(fix.altitude)
                .setSpeedMps(fix.speedMps).setBearingDeg(fix.bearingDeg)
                .setHAccuracyM(fix.hAccuracyM).setVAccuracyM(fix.vAccuracyM)
                .setProvider(fix.provider)
                .build()
            write(Topics.GPS_FIX, fix.timestampNs, msg.toByteArray())
        }
        if (epoch.satellites.isNotEmpty()) {
            val sat = GpsSat.newBuilder().setTimestampNs(epoch.timestampNs)
            epoch.satellites.forEach { s ->
                sat.addSats(
                    GpsSat.Sat.newBuilder()
                        .setSvid(s.svid).setConstellation(s.constellation.name)
                        .setCn0Dbhz(s.cn0DbHz).setUsedInFix(s.usedInFix)
                        .setAzDeg(s.azimuthDeg).setElDeg(s.elevationDeg),
                )
            }
            write(Topics.GPS_SAT, epoch.timestampNs, sat.build().toByteArray())
        }
        if (epoch.rawMeasurements.isNotEmpty()) {
            val raw = GpsRaw.newBuilder().setTimestampNs(epoch.timestampNs)
            epoch.rawMeasurements.forEach { m ->
                raw.addMeasurements(
                    GpsRaw.Measurement.newBuilder()
                        .setSvid(m.svid).setConstellation(m.constellation.name)
                        .setPseudorangeRate(m.pseudorangeRateMps)
                        .setAccumulatedDeltaRange(m.accumulatedDeltaRangeM)
                        .setCn0Dbhz(m.cn0DbHz).setCarrierFreqHz(m.carrierFrequencyHz)
                        .setState(m.state),
                )
            }
            write(Topics.GPS_RAW, epoch.timestampNs, raw.build().toByteArray())
        }
    }

    fun writePose(p: Pose) {
        val msg = PoseProto.newBuilder()
            .setTimestampNs(p.timestampNs)
            .setPosition(p.position.toProto())
            .setOrientation(p.orientation.toProto())
            .setTrackingState(p.trackingState.name)
            .setConfidence(p.confidence)
            .build()
        write(Topics.POSE, p.timestampNs, msg.toByteArray())
    }

    fun writeEnuPose(p: EnuPose) {
        val msg = PoseEnu.newBuilder()
            .setTimestampNs(p.timestampNs)
            .setPosition(Vec3.newBuilder().setX(p.enu.east).setY(p.enu.north).setZ(p.enu.up).build())
            .setOrientation(p.orientation.toProto())
            .setSimTransformId(p.simTransformId)
            .build()
        write(Topics.POSE_ENU, p.timestampNs, msg.toByteArray())
    }

    fun writeLandmarks(ts: Long, landmarks: List<Landmark>) {
        val b = Landmarks.newBuilder().setTimestampNs(ts)
        landmarks.forEach { l ->
            b.addPoints(
                Landmarks.Point.newBuilder()
                    .setId(l.id)
                    .setX(l.position.x.toFloat()).setY(l.position.y.toFloat()).setZ(l.position.z.toFloat())
                    .setConfidence(l.confidence),
            )
        }
        write(Topics.LANDMARKS, ts, b.build().toByteArray())
    }

    fun writeAssets(ts: Long, assets: List<Asset>) {
        val b = Assets.newBuilder().setTimestampNs(ts)
        assets.forEach { a ->
            b.addAssets(
                Assets.Asset.newBuilder()
                    .setId(a.id).setAssetClass(a.assetClass.name)
                    .setLat(a.geo.latitude).setLon(a.geo.longitude).setAlt(a.geo.altitude)
                    .setBbox(
                        Assets.BBox.newBuilder()
                            .setLeft(a.box.left).setTop(a.box.top)
                            .setRight(a.box.right).setBottom(a.box.bottom),
                    )
                    .setConfidence(a.confidence)
                    .setSourceFrameId(a.sourceFrameId)
                    .setDepthM(a.depthM ?: Float.NaN),
            )
        }
        write(Topics.ASSETS, ts, b.build().toByteArray())
    }

    fun writeEvent(e: DeviceEvent) {
        val msg = Event.newBuilder()
            .setTimestampNs(e.timestampNs)
            .setType(e.type.name)
            .setPayload(e.payload)
            .build()
        write(Topics.EVENTS, e.timestampNs, msg.toByteArray())
    }

    fun seal() = writer.sealChunk()

    fun finish() = writer.finish()

    private fun imuTopic(channel: ImuChannel): String = when (channel) {
        ImuChannel.ACCEL -> Topics.IMU_ACCEL
        ImuChannel.GYRO -> Topics.IMU_GYRO
        ImuChannel.MAG -> Topics.IMU_MAG
        ImuChannel.LINEAR_ACCEL -> Topics.IMU_LINEAR_ACCEL
        ImuChannel.GRAVITY -> Topics.IMU_GRAVITY
    }
}

private fun CameraIntrinsics.toProto(): Intrinsics = Intrinsics.newBuilder()
    .setFx(fx).setFy(fy).setCx(cx).setCy(cy)
    .setK1(k1).setK2(k2).setP1(p1).setP2(p2).setK3(k3)
    .build()

private fun com.mappilot.core.model.Vector3.toProto(): Vec3 =
    Vec3.newBuilder().setX(x).setY(y).setZ(z).build()

private fun com.mappilot.core.model.Quaternion.toProto(): Quaternion =
    Quaternion.newBuilder().setX(x).setY(y).setZ(z).setW(w).build()
