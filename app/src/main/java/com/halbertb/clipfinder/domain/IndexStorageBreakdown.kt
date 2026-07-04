package com.halbertb.clipfinder.domain

/** Measured on-device storage for the CLIP image search index. */
data class IndexStorageBreakdown(
    /** Float vector blobs plus estimated SQLite row overhead in image_embeddings. */
    val floatTableBytes: Long,
    /** On-disk compressed index file size (.tvim / .hbvq). */
    val compressedIndexBytes: Long,
    val vectorCount: Int,
) {
    val totalWithFloatsKept: Long
        get() = floatTableBytes + compressedIndexBytes

    val totalAfterRemovingFloats: Long
        get() = compressedIndexBytes

    val savingsIfRemoveFloats: Long
        get() = floatTableBytes

    companion object {
        /** Rough SQLite bytes per image_embeddings row beyond the embedding blob. */
        private const val FLOAT_ROW_OVERHEAD_BYTES = 48L

        fun fromFloatRows(
            rows: List<com.halbertb.clipfinder.data.db.ImageEmbeddingEntity>,
            compressedIndexBytes: Long,
        ): IndexStorageBreakdown {
            val payloadBytes = rows.sumOf { it.embedding.size.toLong() }
            val floatTableBytes = payloadBytes + rows.size * FLOAT_ROW_OVERHEAD_BYTES
            return IndexStorageBreakdown(
                floatTableBytes = floatTableBytes,
                compressedIndexBytes = compressedIndexBytes,
                vectorCount = rows.size,
            )
        }
    }
}
