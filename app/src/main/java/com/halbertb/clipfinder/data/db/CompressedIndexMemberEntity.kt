package com.halbertb.clipfinder.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "compressed_index_members",
    indices = [Index(value = ["dateModifiedSec"])],
)
data class CompressedIndexMemberEntity(
    @PrimaryKey val mediaId: Long,
    val dateModifiedSec: Long,
)
