package com.halbertb.clipfinder.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface AliasPhotoMembershipDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AliasPhotoMembershipEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<AliasPhotoMembershipEntity>)

    @Query(
        """
        INSERT INTO alias_photo_memberships(aliasId, mediaId, confidence, status, provenance, faceCount, updatedAtEpochMs)
        VALUES(:aliasId, :mediaId, :confidence, :status, :provenance, :faceCount, :updatedAtEpochMs)
        ON CONFLICT(aliasId, mediaId) DO UPDATE SET
            confidence = excluded.confidence,
            status = excluded.status,
            provenance = excluded.provenance,
            faceCount = excluded.faceCount,
            updatedAtEpochMs = excluded.updatedAtEpochMs
        WHERE alias_photo_memberships.provenance != 'user_feedback'
        """,
    )
    suspend fun upsertModel(
        aliasId: Long,
        mediaId: Long,
        confidence: Float,
        status: String,
        provenance: String,
        faceCount: Int,
        updatedAtEpochMs: Long,
    )

    @Transaction
    suspend fun upsertAllModel(entities: List<AliasPhotoMembershipEntity>) {
        for (entity in entities) {
            upsertModel(
                aliasId = entity.aliasId,
                mediaId = entity.mediaId,
                confidence = entity.confidence,
                status = entity.status,
                provenance = entity.provenance,
                faceCount = entity.faceCount,
                updatedAtEpochMs = entity.updatedAtEpochMs,
            )
        }
    }

    @Query("SELECT * FROM alias_photo_memberships WHERE aliasId = :aliasId")
    suspend fun getByAliasId(aliasId: Long): List<AliasPhotoMembershipEntity>

    @Query(
        """
        SELECT * FROM alias_photo_memberships
        WHERE aliasId = :aliasId
          AND status NOT IN ('user_confirmed', 'user_rejected')
        ORDER BY confidence DESC, updatedAtEpochMs DESC, mediaId DESC
        LIMIT :limit
        """,
    )
    suspend fun getPendingPreview(aliasId: Long, limit: Int): List<AliasPhotoMembershipEntity>

    @Query("SELECT mediaId FROM alias_photo_memberships WHERE aliasId = :aliasId AND status IN ('matched', 'user_confirmed')")
    suspend fun getMatchedMediaIds(aliasId: Long): List<Long>

    @Query("SELECT mediaId, confidence FROM alias_photo_memberships WHERE aliasId = :aliasId AND status IN ('matched', 'user_confirmed')")
    suspend fun getMatchedMediaConfidences(aliasId: Long): List<MediaConfidence>

    @Query("SELECT confidence FROM alias_photo_memberships WHERE aliasId = :aliasId AND mediaId = :mediaId LIMIT 1")
    suspend fun getConfidenceFor(aliasId: Long, mediaId: Long): Float?

    @Query("DELETE FROM alias_photo_memberships WHERE aliasId = :aliasId")
    suspend fun deleteByAliasId(aliasId: Long)

    @Query("DELETE FROM alias_photo_memberships WHERE mediaId IN (:mediaIds)")
    suspend fun deleteByMediaIds(mediaIds: List<Long>)

    @Query("DELETE FROM alias_photo_memberships")
    suspend fun deleteAll()

    @Query(
        """
        SELECT
          COALESCE(SUM(CASE WHEN status IN ('matched', 'user_confirmed') THEN 1 ELSE 0 END), 0) AS includedCount,
          COALESCE(SUM(CASE WHEN status IN ('rejected', 'user_rejected', 'uncertain') THEN 1 ELSE 0 END), 0) AS notIncludedCount,
          COALESCE(SUM(CASE WHEN status = 'error' THEN 1 ELSE 0 END), 0) AS errorCount
        FROM alias_photo_memberships
        WHERE aliasId = :aliasId
        """,
    )
    suspend fun getCounts(aliasId: Long): AliasMembershipCounts
}

data class AliasMembershipCounts(
    val includedCount: Int,
    val notIncludedCount: Int,
    val errorCount: Int,
)

data class MediaConfidence(
    val mediaId: Long,
    val confidence: Float,
)
