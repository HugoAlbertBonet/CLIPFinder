package com.halbertb.clipfinder.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "alias_refinement_state")
data class AliasRefinementStateEntity(
    @PrimaryKey val aliasId: Long,
    val lastProcessedMediaId: Long?,
    val processedCount: Int,
    val totalCount: Int,
    val running: Boolean,
    val updatedAtEpochMs: Long,
)
