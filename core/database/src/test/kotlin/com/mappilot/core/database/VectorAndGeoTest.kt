package com.mappilot.core.database

import com.google.common.truth.Truth.assertThat
import com.mappilot.core.database.vector.VectorMath
import org.junit.Test

class VectorAndGeoTest {

    @Test
    fun `encode then decode round-trips a vector`() {
        val v = floatArrayOf(0.1f, -0.2f, 0.3f, 0.9f)
        val back = VectorMath.decode(VectorMath.encode(v))
        assertThat(back).usingExactEquality().containsExactly(0.1f, -0.2f, 0.3f, 0.9f).inOrder()
    }

    @Test
    fun `cosine similarity is 1 for identical and 0 for orthogonal`() {
        val a = floatArrayOf(1f, 2f, 3f)
        assertThat(VectorMath.cosineSimilarity(a, a)).isWithin(1e-6f).of(1f)
        assertThat(VectorMath.cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f))).isWithin(1e-6f).of(0f)
    }

    @Test
    fun `topK ranks nearest embeddings first`() {
        val query = floatArrayOf(1f, 0f, 0f)
        val candidates = listOf(
            1L to floatArrayOf(0f, 1f, 0f),   // orthogonal
            2L to floatArrayOf(0.9f, 0.1f, 0f), // close
            3L to floatArrayOf(-1f, 0f, 0f),  // opposite
        )
        val top = VectorMath.topK(query, candidates, k = 2)
        assertThat(top.map { it.id }).containsExactly(2L, 1L).inOrder()
        assertThat(top.first().score).isGreaterThan(top.last().score)
    }

    @Test
    fun `bounding box widens with radius and latitude`() {
        val near = SpatialQueries.boundingBox(0.0, 0.0, 100.0)   // equator
        val high = SpatialQueries.boundingBox(60.0, 0.0, 100.0)  // 60°N
        val lonSpanNear = near[3] - near[2]
        val lonSpanHigh = high[3] - high[2]
        // Same metric radius spans more longitude degrees at higher latitude.
        assertThat(lonSpanHigh).isGreaterThan(lonSpanNear)
    }

    @Test
    fun `haversine matches known short distance`() {
        // ~0.001 deg latitude ≈ 111.32 m
        val d = Haversine.distanceM(12.9716, 77.5946, 12.9716 + 0.001, 77.5946)
        assertThat(d).isWithin(2.0).of(111.32)
    }
}
