package com.halbertb.clipfinder.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Single-row metadata for the on-disk compressed CLIP index. */
@Entity(tableName = "compressed_index_manifest")
data class CompressedIndexManifestEntity(
    @PrimaryKey val id: Int = 1,
    /** Matches [com.halbertb.clipfinder.domain.SearchCompressionMode.prefValue]. */
    val modePref: String,
    val filePath: String,
    val dimension: Int,
    val vectorCount: Int,
    val builtAtEpochMs: Long,
    /** True when float rows in image_embeddings were removed after compress. */
    val floatsRemoved: Boolean,
)
