package com.mappilot.core.database

import com.mappilot.core.database.entity.AssetEntity
import com.mappilot.core.database.entity.GnssFixEntity
import com.mappilot.core.database.entity.TripEntity
import com.mappilot.core.model.Asset
import com.mappilot.core.model.AssetClass
import com.mappilot.core.model.BoundingBox
import com.mappilot.core.model.GeoPoint
import com.mappilot.core.model.GnssFix
import com.mappilot.core.model.Provenance
import com.mappilot.core.model.Trip
import com.mappilot.core.model.TripStatus

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

fun GnssFixEntity.toDomain(): GnssFix = GnssFix(
    timestampNs = timestampNs,
    latitude = lat, longitude = lon, altitude = alt,
    speedMps = speedMps, bearingDeg = bearingDeg,
    hAccuracyM = hAccuracyM, vAccuracyM = vAccuracyM,
    provider = "db",
)
