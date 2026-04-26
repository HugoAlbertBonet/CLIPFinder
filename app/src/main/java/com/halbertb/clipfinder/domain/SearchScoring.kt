package com.halbertb.clipfinder.domain

import com.halbertb.clipfinder.data.db.ImageEmbeddingEntity
import com.halbertb.clipfinder.ml.dot
import com.halbertb.clipfinder.ml.littleEndianBytesToFloatArray

private const val LAMBDA = 1f

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
    val scored =
        rows.map { row ->
            val img = littleEndianBytesToFloatArray(row.embedding)
            val posDot = dot(img, positive)
            val negDot = if (negative == null) 0f else dot(img, negative)
            val score =
                if (negative == null) {
                    posDot
                } else {
                    posDot - LAMBDA * negDot
                }
            ScoredImage(mediaId = row.mediaId, score = score)
        }
    return scored.sortedByDescending { it.score }.take(k.coerceAtLeast(1))
}

fun filterRowsByAllowedMediaIds(
    rows: List<ImageEmbeddingEntity>,
    allowedMediaIds: Set<Long>,
): List<ImageEmbeddingEntity> {
    if (allowedMediaIds.isEmpty()) return emptyList()
    return rows.filter { it.mediaId in allowedMediaIds }
}
