package com.mappilot.core.database.vector

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Vector encoding + exact cosine similarity. This is the correct, always-available
 * vector-search implementation (O(n) over stored embeddings — fine at on-device
 * asset scale). The `sqlite-vec` extension is the accelerated backend seam for
 * large multi-session sets; it does not change results, only speed.
 */
object VectorMath {

    fun encode(vector: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        vector.forEach { buf.putFloat(it) }
        return buf.array()
    }

    fun decode(bytes: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buf.float }
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "dim mismatch ${a.size} != ${b.size}" }
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]
        }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom == 0.0) 0.0f else (dot / denom).toFloat()
    }

    data class Scored(val id: Long, val score: Float)

    /** Top-[k] embeddings by cosine similarity to [query]. */
    fun topK(query: FloatArray, candidates: List<Pair<Long, FloatArray>>, k: Int): List<Scored> =
        candidates.asSequence()
            .map { (id, vec) -> Scored(id, cosineSimilarity(query, vec)) }
            .sortedByDescending { it.score }
            .take(k)
            .toList()
}
