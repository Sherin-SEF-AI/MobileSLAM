package com.mappilot.core.database

import com.mappilot.core.database.entity.AssetEntity
import com.mappilot.core.database.entity.GnssFixEntity
import com.mappilot.core.database.entity.KeyframeEntity
import com.mappilot.core.database.entity.TripEntity
import com.mappilot.core.model.Asset
import com.mappilot.core.model.AssetClass
import com.mappilot.core.model.BoundingBox
import com.mappilot.core.model.CameraIntrinsics
import com.mappilot.core.model.EnuPoint
import com.mappilot.core.model.EnuPose
import com.mappilot.core.model.GeoPoint
import com.mappilot.core.model.GnssFix
import com.mappilot.core.model.Keyframe
import com.mappilot.core.model.Pose
import com.mappilot.core.model.Provenance
import com.mappilot.core.model.Quaternion
import com.mappilot.core.model.TrackingFailureReason
import com.mappilot.core.model.TrackingState
import com.mappilot.core.model.Trip
import com.mappilot.core.model.TripStatus
import com.mappilot.core.model.Vector3

fun AssetEntity.toDomain(): Asset = Asset(
    id = id,
    assetClass = runCatching { AssetClass.valueOf(assetClass) }.getOrDefault(AssetClass.UNKNOWN),
    geo = GeoPoint(lat, lon, alt),
    box = BoundingBox(bboxLeft, bboxTop, bboxRight, bboxBottom),
    confidence = confidence,
    sourceFrameId = sourceFrameId,
    depthM = depthM,
    embeddingId = embeddingId,
)

fun Asset.toEntity(tripId: Long, embeddingId: Long?): AssetEntity = AssetEntity(
    id = if (id > 0) id else 0,
    tripId = tripId,
    assetClass = assetClass.name,
    lat = geo.latitude,
    lon = geo.longitude,
    alt = geo.altitude,
    bboxLeft = box.left, bboxTop = box.top, bboxRight = box.right, bboxBottom = box.bottom,
    confidence = confidence,
    sourceFrameId = sourceFrameId,
    depthM = depthM,
    embeddingId = embeddingId,
)

fun TripEntity.toDomain(): Trip = Trip(
    id = id,
    startedNs = startedNs,
    endedNs = endedNs,
    distanceM = distanceM,
    areaM2 = areaM2,
    slamScore = slamScore,
    gnssScore = gnssScore,
    mcapPath = mcapPath,
    mp4Path = mp4Path,
    status = runCatching { TripStatus.valueOf(status) }.getOrDefault(TripStatus.RECORDED),
    provenance = runCatching { Provenance.valueOf(provenance) }.getOrDefault(Provenance.ON_DEVICE),
)

fun Trip.toEntity(): TripEntity = TripEntity(
    id = if (id > 0) id else 0,
    startedNs = startedNs,
    endedNs = endedNs,
    distanceM = distanceM,
    areaM2 = areaM2,
    slamScore = slamScore,
    gnssScore = gnssScore,
    mcapPath = mcapPath,
    mp4Path = mp4Path,
    status = status.name,
    provenance = provenance.name,
)

fun com.mappilot.core.database.entity.UploadJobEntity.toUploadDomain(): com.mappilot.core.model.UploadJob =
    com.mappilot.core.model.UploadJob(
        id = id, tripId = tripId, artifact = artifact, remoteId = remoteId,
        state = state, bytesSent = bytesSent, totalBytes = totalBytes,
        provenance = runCatching { Provenance.valueOf(provenance) }.getOrDefault(Provenance.CLOUD_REFINED),
    )

fun GnssFixEntity.toDomain(): GnssFix = GnssFix(
    timestampNs = timestampNs,
    latitude = lat, longitude = lon, altitude = alt,
    speedMps = speedMps, bearingDeg = bearingDeg,
    hAccuracyM = hAccuracyM, vAccuracyM = vAccuracyM,
    provider = "db",
)

fun KeyframeEntity.toDomain(): Keyframe = Keyframe(
    frameId = frameId,
    timestampNs = timestampNs,
    pose = Pose(
        timestampNs = timestampNs,
        position = Vector3(px, py, pz),
        orientation = Quaternion(qx, qy, qz, qw),
        trackingState = TrackingState.TRACKING,
        failureReason = TrackingFailureReason.NONE,
        confidence = 1f,
    ),
    enuPose = if (east != null && north != null && up != null) {
        EnuPose(timestampNs, EnuPoint(east, north, up), Quaternion(qx, qy, qz, qw), simTransformId = 0)
    } else {
        null
    },
    intrinsics = if (fx != null && fy != null && cx != null && cy != null) {
        // Image dimensions aren't persisted per-keyframe; 0 marks them unknown.
        CameraIntrinsics(fx = fx, fy = fy, cx = cx, cy = cy, imageWidth = 0, imageHeight = 0)
    } else {
        null
    },
)
