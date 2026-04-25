package com.halbertb.clipfinder.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "image_embeddings",
    indices = [Index(value = ["dateModifiedSec"])],
)
data class ImageEmbeddingEntity(
    @PrimaryKey val mediaId: Long,
    val dateModifiedSec: Long,
    /** 512 floats stored as little-endian bytes (512 * 4 bytes), L2-normalized */
    val embedding: ByteArray,
    val indexedAtEpochMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ImageEmbeddingEntity
        if (mediaId != other.mediaId) return false
        if (dateModifiedSec != other.dateModifiedSec) return false
        if (!embedding.contentEquals(other.embedding)) return false
        if (indexedAtEpochMs != other.indexedAtEpochMs) return false
        return true
    }

    override fun hashCode(): Int {
        var result = mediaId.hashCode()
        result = 31 * result + dateModifiedSec.hashCode()
        result = 31 * result + embedding.contentHashCode()
        result = 31 * result + indexedAtEpochMs.hashCode()
        return result
    }
}
