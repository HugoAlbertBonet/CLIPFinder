package com.halbertb.clipfinder.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "alias_reference_faces",
    foreignKeys = [
        ForeignKey(
            entity = PersonAliasEntity::class,
            parentColumns = ["aliasId"],
            childColumns = ["aliasId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["aliasId"])],
)
data class AliasReferenceFaceEntity(
    @PrimaryKey(autoGenerate = true) val referenceId: Long = 0L,
    val aliasId: Long,
    /** 512 floats stored as little-endian bytes, L2-normalized */
    val embedding: ByteArray,
    val sourceUri: String?,
    val createdAtEpochMs: Long,
)
