package com.halbertb.clipfinder.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "person_aliases",
    indices = [Index(value = ["normalizedAlias"], unique = true)],
)
data class PersonAliasEntity(
    @PrimaryKey(autoGenerate = true) val aliasId: Long = 0L,
    val alias: String,
    val normalizedAlias: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    /**
     * Cosine similarity above which a face is considered the alias.
     * User-tunable from the People tab. Calibrated for MobileFaceNet embeddings.
     */
    val matchThreshold: Float = 0.55f,
)
