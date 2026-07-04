package com.halbertb.clipfinder.domain

import com.halbertb.clipfinder.data.db.ImageEmbeddingEntity
import com.halbertb.clipfinder.ml.dot
import com.halbertb.clipfinder.ml.littleEndianBytesToFloatArray
import java.util.PriorityQueue

private const val LAMBDA = 1f

fun combinedSearchQuery(
    positive: FloatArray,
    negative: FloatArray?,
): FloatArray =
    if (negative == null) {
        positive
    } else {
        FloatArray(positive.size) { i -> positive[i] - LAMBDA * negative[i] }
    }

data class ScoredImage(
    val mediaId: Long,
    val score: Float,
)

fun scoreIndexedImages(
    rows: List<ImageEmbeddingEntity>,
    positive: FloatArray,
    negative: FloatArray?,
    k: Int,
): List<ScoredImage> {
    val limit = k.coerceAtLeast(1)
    val heap = PriorityQueue<ScoredImage>(compareBy { it.score })
    for (row in rows) {
        val img = littleEndianBytesToFloatArray(row.embedding)
        val posDot = dot(img, positive)
        val negDot = if (negative == null) 0f else dot(img, negative)
        val score =
            if (negative == null) {
                posDot
            } else {
                posDot - LAMBDA * negDot
            }
        val item = ScoredImage(mediaId = row.mediaId, score = score)
        if (heap.size < limit) {
            heap.add(item)
        } else if (score > (heap.peek()?.score ?: Float.NEGATIVE_INFINITY)) {
            heap.poll()
            heap.add(item)
        }
    }
    return heap.toList().sortedByDescending { it.score }
}

fun filterRowsByAllowedMediaIds(
    rows: List<ImageEmbeddingEntity>,
    allowedMediaIds: Set<Long>,
): List<ImageEmbeddingEntity> {
    if (allowedMediaIds.isEmpty()) return emptyList()
    return rows.filter { it.mediaId in allowedMediaIds }
}
