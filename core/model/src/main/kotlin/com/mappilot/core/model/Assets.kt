package com.mappilot.core.model

/** Road-asset categories extracted on-device. */
enum class AssetClass {
    TRAFFIC_SIGN,
    TRAFFIC_LIGHT,
    POLE,
    ROAD_MARKING,
    SPEED_BREAKER,
    POTHOLE,
    LANE_MARKING,
    CROSSWALK,
    CONSTRUCTION_ZONE,
    UNKNOWN,
}

/** Axis-aligned detection box in image pixels. */
data class BoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/** A single object detection on one frame, before geolocation. */
data class Detection(
    val assetClass: AssetClass,
    val rawLabel: String,
    val box: BoundingBox,
    val confidence: Float,
    val sourceFrameId: Long,
    val timestampNs: Long,
)

/**
 * A georeferenced asset: a detection that has been deduplicated across frames
 * and backprojected to a world position. [depthM] is the depth used for
 * backprojection — null when depth was unavailable (asset then carries a
 * DEGRADED geolocation quality, never a fabricated one).
 */
data class Asset(
    val id: Long,
    val assetClass: AssetClass,
    val geo: GeoPoint,
    val box: BoundingBox,
    val confidence: Float,
    val sourceFrameId: Long,
    val depthM: Float?,
    val embeddingId: Long?,
    /** Majority ARCore Scene Semantics label at the asset (e.g. ROAD, SIDEWALK); null if none. */
    val semanticLabel: String? = null,
    /** Isotropic 1-sigma positional uncertainty (m) from multi-observation covariance; null if single-shot. */
    val positionStdM: Float? = null,
)
