package com.mappilot.analytics

import com.google.common.truth.Truth.assertThat
import com.mappilot.core.model.GeoPoint
import org.junit.Test

class AnalyticsTest {

    private fun geo(lat: Double, lon: Double) = GeoPoint(lat, lon, 920.0)

    @Test
    fun `coverage area of a 100m square is about 10000 m2`() {
        // ~0.000898 deg lat ≈ 100 m; lon adjusted by cos(lat).
        val dLat = 100.0 / 110_574.0
        val dLon = 100.0 / (111_320.0 * Math.cos(Math.toRadians(12.97)))
        val square = listOf(
            geo(12.97, 77.59),
            geo(12.97 + dLat, 77.59),
            geo(12.97 + dLat, 77.59 + dLon),
            geo(12.97, 77.59 + dLon),
        )
        val area = GeoGeometry.coverageAreaM2(square)
        assertThat(area).isWithin(200.0).of(10_000.0)
    }

    @Test
    fun `coverage area is zero for fewer than three points`() {
        assertThat(GeoGeometry.coverageAreaM2(listOf(geo(0.0, 0.0), geo(1.0, 1.0)))).isEqualTo(0.0)
    }

    @Test
    fun `collinear points have near-zero area`() {
        val line = (0..10).map { geo(12.97 + it * 0.0001, 77.59) }
        assertThat(GeoGeometry.coverageAreaM2(line)).isLessThan(1.0)
    }

    private fun metrics(
        tracking: Double = 1.0, fix: Double = 1.0, cn0: Double = 40.0, sats: Double = 10.0,
        hacc: Double = 3.0, georef: Boolean = true, rms: Double = 1.0, drops: Long = 0, total: Long = 100_000,
    ) = SessionMetrics(
        durationNs = 60_000_000_000, distanceM = 500.0,
        trajectoryGeo = listOf(geo(12.97, 77.59), geo(12.975, 77.595), geo(12.97, 77.60)),
        trackingFraction = tracking, keyframeCount = 120, landmarkCount = 8000,
        fixFraction = fix, meanCn0DbHz = cn0, meanSatsUsed = sats, meanHAccuracyM = hacc,
        syncDriftCount = 0, droppedSamples = drops, totalSamples = total,
        assetCount = 12, georeferenced = georef, alignmentRmsM = rms,
    )

    @Test
    fun `high-quality session scores high across the board`() {
        val r = QualityAnalyzer.analyze(metrics())
        assertThat(r.slamScore).isGreaterThan(0.8f)
        assertThat(r.gnssScore).isGreaterThan(0.7f)
        assertThat(r.trajectoryScore).isGreaterThan(0.7f)
        assertThat(r.overall).isGreaterThan(0.7f)
        assertThat(r.coverageAreaM2).isGreaterThan(0.0)
    }

    @Test
    fun `lost tracking lowers the slam score`() {
        val good = QualityAnalyzer.analyze(metrics(tracking = 1.0)).slamScore
        val bad = QualityAnalyzer.analyze(metrics(tracking = 0.3)).slamScore
        assertThat(bad).isLessThan(good)
    }

    @Test
    fun `no fix and weak signal lowers the gnss score`() {
        val good = QualityAnalyzer.analyze(metrics(fix = 1.0, cn0 = 45.0, sats = 12.0)).gnssScore
        val bad = QualityAnalyzer.analyze(metrics(fix = 0.1, cn0 = 22.0, sats = 3.0)).gnssScore
        assertThat(bad).isLessThan(good)
    }

    @Test
    fun `unaligned session has low trajectory and reconstruction scores`() {
        val r = QualityAnalyzer.analyze(metrics(georef = false))
        assertThat(r.trajectoryScore).isLessThan(0.65f)
        assertThat(r.reconstructionReadiness).isLessThan(0.8f)
    }

    @Test
    fun `heavy sample drops reduce trajectory score`() {
        val clean = QualityAnalyzer.analyze(metrics(drops = 0)).trajectoryScore
        val lossy = QualityAnalyzer.analyze(metrics(drops = 20_000, total = 100_000)).trajectoryScore
        assertThat(lossy).isLessThan(clean)
    }

    @Test
    fun `all scores stay within 0 and 1`() {
        val r = QualityAnalyzer.analyze(metrics(tracking = 2.0, cn0 = 100.0, sats = 99.0, hacc = -1.0))
        listOf(r.slamScore, r.gnssScore, r.trajectoryScore, r.reconstructionReadiness, r.overall).forEach {
            assertThat(it).isAtLeast(0f); assertThat(it).isAtMost(1f)
        }
    }
}
