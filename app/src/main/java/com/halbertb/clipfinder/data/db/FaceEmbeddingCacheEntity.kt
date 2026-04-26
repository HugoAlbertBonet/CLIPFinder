package com.halbertb.clipfinder.data.db

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "face_embedding_cache",
    primaryKeys = ["mediaId", "dateModifiedSec", "cacheVersion"],
    indices = [Index(value = ["mediaId"])],
)
data class FaceEmbeddingCacheEntity(
    val mediaId: Long,
    val dateModifiedSec: Long,
    val cacheVersion: Int,
    /** N face embeddings serialized as little-endian float32 values (N * 512 * 4 bytes). */
    val embeddingsBlob: ByteArray,
    val faceCount: Int,
    val indexedAtEpochMs: Long,
)
