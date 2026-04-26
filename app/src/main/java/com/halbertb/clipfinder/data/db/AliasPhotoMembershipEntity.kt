package com.halbertb.clipfinder.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "alias_photo_memberships",
    primaryKeys = ["aliasId", "mediaId"],
    foreignKeys = [
        ForeignKey(
            entity = PersonAliasEntity::class,
            parentColumns = ["aliasId"],
            childColumns = ["aliasId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["aliasId"]), Index(value = ["mediaId"])],
)
data class AliasPhotoMembershipEntity(
    val aliasId: Long,
    val mediaId: Long,
    val confidence: Float,
    /** matched, rejected, uncertain, user_confirmed, user_rejected */
    val status: String,
    /** preview, incremental, full_refinement, user_feedback */
    val provenance: String,
    val faceCount: Int,
    val updatedAtEpochMs: Long,
)
