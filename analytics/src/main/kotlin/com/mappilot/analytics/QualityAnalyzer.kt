package com.mappilot.analytics

import com.mappilot.core.model.GeoPoint
import kotlin.math.min

/** Raw, measured session inputs the analyzer scores. Every field comes from real capture. */
data class SessionMetrics(
    val durationNs: Long,
    val distanceM: Double,
    val trajectoryGeo: List<GeoPoint>,
    // SLAM
    val trackingFraction: Double,   // fraction of poses with TRACKING state, 0..1
    val keyframeCount: Int,
    val landmarkCount: Int,
    // GNSS
    val fixFraction: Double,        // fraction of epochs with a fix, 0..1
    val meanCn0DbHz: Double,
    val meanSatsUsed: Double,
    val meanHAccuracyM: Double,     // <0 if unknown
    // Sync
    val syncDriftCount: Long,
    val droppedSamples: Long,
    val totalSamples: Long,
    // Assets
    val assetCount: Int,
    // Georeferencing
    val georeferenced: Boolean,
    val alignmentRmsM: Double,      // NaN if not aligned
)

/** Scored quality report, all sub-scores in 0..1. */
data class QualityReport(
    val slamScore: Float,
    val gnssScore: Float,
    val trajectoryScore: Float,
    val coverageAreaM2: Double,
    val distanceM: Double,
    val assetCount: Int,
    val keyframeCount: Int,
    val landmarkCount: Int,
    val reconstructionReadiness: Float,
    val overall: Float,
)

/**
 * Computes quality scores from real session metrics. All scoring is bounded,
 * monotonic, and documented; nothing is invented — a metric that was not measured
 * (e.g. unknown GNSS accuracy) is excluded from its sub-score rather than guessed.
 */
object QualityAnalyzer {

    fun analyze(m: SessionMetrics): QualityReport {
        val slam = slamScore(m)
        val gnss = gnssScore(m)
        val coverage = GeoGeometry.coverageAreaM2(m.trajectoryGeo)
        val trajectory = trajectoryScore(m)
        val reconstruction = reconstructionReadiness(m, coverage, slam, gnss)
        val overall = (slam * 0.3f + gnss * 0.25f + trajectory * 0.25f + reconstruction * 0.2f)
        return QualityReport(
            slamScore = slam,
            gnssScore = gnss,
            trajectoryScore = trajectory,
            coverageAreaM2 = coverage,
            distanceM = m.distanceM,
            assetCount = m.assetCount,
            keyframeCount = m.keyframeCount,
            landmarkCount = m.landmarkCount,
            reconstructionReadiness = reconstruction,
            overall = overall.coerceIn(0f, 1f),
        )
    }

    /** Tracking continuity + feature richness (keyframe + landmark density vs distance). */
    private fun slamScore(m: SessionMetrics): Float {
        val tracking = m.trackingFraction.coerceIn(0.0, 1.0)
        val kfDensity = if (m.distanceM > 1) min(1.0, m.keyframeCount / (m.distanceM / 5.0)) else 0.0
        val lmDensity = if (m.keyframeCount > 0) min(1.0, m.landmarkCount / (m.keyframeCount * 50.0)) else 0.0
        return (0.6 * tracking + 0.25 * kfDensity + 0.15 * lmDensity).toFloat().coerceIn(0f, 1f)
    }

    /** Fix availability + signal strength + satellite count + horizontal accuracy. */
    private fun gnssScore(m: SessionMetrics): Float {
        val fix = m.fixFraction.coerceIn(0.0, 1.0)
        val cn0 = ((m.meanCn0DbHz - 20.0) / 25.0).coerceIn(0.0, 1.0) // 20→0, 45→1 dBHz
        val sats = (m.meanSatsUsed / 12.0).coerceIn(0.0, 1.0)
        val acc = if (m.meanHAccuracyM < 0) null else (1.0 - (m.meanHAccuracyM / 20.0)).coerceIn(0.0, 1.0)
        return if (acc == null) {
            (0.5 * fix + 0.3 * cn0 + 0.2 * sats).toFloat()
        } else {
            (0.4 * fix + 0.25 * cn0 + 0.15 * sats + 0.2 * acc).toFloat()
        }.coerceIn(0f, 1f)
    }

    /** Sync health (drift/drops) + georeferencing residual. */
    private fun trajectoryScore(m: SessionMetrics): Float {
        val dropRate = if (m.totalSamples > 0) m.droppedSamples.toDouble() / m.totalSamples else 0.0
        val syncHealth = (1.0 - dropRate * 10).coerceIn(0.0, 1.0) // 10% drops → 0
        val driftPenalty = (1.0 - m.syncDriftCount / 20.0).coerceIn(0.0, 1.0)
        val georef = when {
            !m.georeferenced -> 0.0
            m.alignmentRmsM.isNaN() -> 0.5
            else -> (1.0 - m.alignmentRmsM / 5.0).coerceIn(0.0, 1.0) // 5 m RMS → 0
        }
        return (0.4 * syncHealth + 0.2 * driftPenalty + 0.4 * georef).toFloat().coerceIn(0f, 1f)
    }

    /** Composite readiness for offline reconstruction (coverage, features, georef). */
    private fun reconstructionReadiness(m: SessionMetrics, coverageM2: Double, slam: Float, gnss: Float): Float {
        val coverage = min(1.0, coverageM2 / 1000.0) // 1000 m² → full
        val features = if (m.distanceM > 1) min(1.0, m.landmarkCount / (m.distanceM * 100.0)) else 0.0
        val georef = if (m.georeferenced) 1.0 else 0.0
        return (0.3 * coverage + 0.3 * features + 0.2 * slam + 0.1 * gnss + 0.1 * georef).toFloat().coerceIn(0f, 1f)
    }
}
