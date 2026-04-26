package com.halbertb.clipfinder.data.person

import android.net.Uri
import com.halbertb.clipfinder.data.db.AliasMembershipCounts
import com.halbertb.clipfinder.data.db.AliasPhotoMembershipDao
import com.halbertb.clipfinder.data.db.AliasPhotoMembershipEntity
import com.halbertb.clipfinder.data.db.MediaConfidence
import com.halbertb.clipfinder.data.db.AliasReferenceFaceDao
import com.halbertb.clipfinder.data.db.AliasReferenceFaceEntity
import com.halbertb.clipfinder.data.db.AliasRefinementStateDao
import com.halbertb.clipfinder.data.db.AliasRefinementStateEntity
import com.halbertb.clipfinder.data.db.FaceEmbeddingCacheDao
import com.halbertb.clipfinder.data.db.FaceEmbeddingCacheEntity
import com.halbertb.clipfinder.data.db.PersonAliasDao
import com.halbertb.clipfinder.data.db.PersonAliasEntity
import com.halbertb.clipfinder.ml.blobToFaceEmbeddings
import com.halbertb.clipfinder.ml.faceEmbeddingsToBlob
import com.halbertb.clipfinder.ml.floatArrayToLittleEndianBytes
import com.halbertb.clipfinder.ml.littleEndianBytesToFloatArray
import java.util.Locale

class PersonAliasRepository(
    private val aliasDao: PersonAliasDao,
    private val referenceDao: AliasReferenceFaceDao,
    private val membershipDao: AliasPhotoMembershipDao,
    private val refinementStateDao: AliasRefinementStateDao,
    private val faceEmbeddingCacheDao: FaceEmbeddingCacheDao,
) {
    suspend fun deleteAllAliasData() {
        refinementStateDao.deleteAll()
        membershipDao.deleteAll()
        referenceDao.deleteAll()
        aliasDao.deleteAll()
        faceEmbeddingCacheDao.deleteAll()
    }

    suspend fun listAliases(): List<PersonAliasEntity> = aliasDao.getAll()

    suspend fun getAliasByNormalized(normalizedAlias: String): PersonAliasEntity? =
        aliasDao.getByNormalizedAlias(normalizedAlias)

    suspend fun deleteAlias(aliasId: Long) {
        aliasDao.deleteById(aliasId)
    }

    suspend fun createAlias(alias: String): PersonAliasEntity {
        val now = System.currentTimeMillis()
        val normalized = normalizeAlias(alias)
        val id =
            aliasDao.insert(
                PersonAliasEntity(
                    alias = alias.trim(),
                    normalizedAlias = normalized,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                ),
            )
        return aliasDao.getById(id) ?: error("Alias creation failed")
    }

    suspend fun upsertAliasReferences(aliasId: Long, embeddings: List<Pair<FloatArray, Uri?>>) {
        referenceDao.deleteByAliasId(aliasId)
        val now = System.currentTimeMillis()
        referenceDao.upsertAll(
            embeddings.map { (vec, uri) ->
                AliasReferenceFaceEntity(
                    aliasId = aliasId,
                    embedding = floatArrayToLittleEndianBytes(vec),
                    sourceUri = uri?.toString(),
                    createdAtEpochMs = now,
                )
            },
        )
    }

    suspend fun getAliasReferenceEmbeddings(aliasId: Long): List<FloatArray> =
        referenceDao.getByAliasId(aliasId).map { littleEndianBytesToFloatArray(it.embedding) }

    suspend fun upsertMemberships(rows: List<AliasPhotoMembershipEntity>) {
        if (rows.isNotEmpty()) membershipDao.upsertAllModel(rows)
    }

    suspend fun listMatchedMediaIds(aliasId: Long): List<Long> = membershipDao.getMatchedMediaIds(aliasId)

    suspend fun listMatchedMediaConfidences(aliasId: Long): List<MediaConfidence> =
        membershipDao.getMatchedMediaConfidences(aliasId)

    suspend fun getConfidence(aliasId: Long, mediaId: Long): Float? =
        membershipDao.getConfidenceFor(aliasId, mediaId)

    suspend fun setAliasMatchThreshold(aliasId: Long, threshold: Float) {
        aliasDao.updateThreshold(aliasId, threshold, System.currentTimeMillis())
    }

    suspend fun getAliasById(aliasId: Long): PersonAliasEntity? = aliasDao.getById(aliasId)

    suspend fun listMembership(aliasId: Long): List<AliasPhotoMembershipEntity> = membershipDao.getByAliasId(aliasId)

    suspend fun listPendingPreview(aliasId: Long, limit: Int): List<AliasPhotoMembershipEntity> =
        membershipDao.getPendingPreview(aliasId, limit)

    suspend fun getMembershipCounts(aliasId: Long): AliasMembershipCounts = membershipDao.getCounts(aliasId)

    suspend fun removeMembershipForDeletedMedia(mediaIds: List<Long>) {
        if (mediaIds.isNotEmpty()) membershipDao.deleteByMediaIds(mediaIds)
    }

    suspend fun removeFaceEmbeddingCacheForDeletedMedia(mediaIds: List<Long>) {
        if (mediaIds.isNotEmpty()) faceEmbeddingCacheDao.deleteByMediaIds(mediaIds)
    }

    suspend fun updateRefinementState(entity: AliasRefinementStateEntity) = refinementStateDao.upsert(entity)

    suspend fun getRefinementState(aliasId: Long): AliasRefinementStateEntity? = refinementStateDao.getByAliasId(aliasId)

    suspend fun getFaceEmbeddingCache(
        mediaId: Long,
        dateModifiedSec: Long,
        cacheVersion: Int,
    ): List<FloatArray>? {
        val row = faceEmbeddingCacheDao.get(mediaId, dateModifiedSec, cacheVersion) ?: return null
        return if (row.faceCount <= 0) emptyList() else blobToFaceEmbeddings(row.embeddingsBlob)
    }

    suspend fun upsertFaceEmbeddingCache(
        mediaId: Long,
        dateModifiedSec: Long,
        cacheVersion: Int,
        embeddings: List<FloatArray>,
    ) {
        faceEmbeddingCacheDao.upsert(
            FaceEmbeddingCacheEntity(
                mediaId = mediaId,
                dateModifiedSec = dateModifiedSec,
                cacheVersion = cacheVersion,
                embeddingsBlob = faceEmbeddingsToBlob(embeddings),
                faceCount = embeddings.size,
                indexedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun markFeedback(aliasId: Long, mediaId: Long, accepted: Boolean) {
        val existing = membershipDao.getByAliasId(aliasId).firstOrNull { it.mediaId == mediaId }
        val now = System.currentTimeMillis()
        val row =
            AliasPhotoMembershipEntity(
                aliasId = aliasId,
                mediaId = mediaId,
                confidence = existing?.confidence ?: if (accepted) 1f else 0f,
                status = if (accepted) "user_confirmed" else "user_rejected",
                provenance = "user_feedback",
                faceCount = existing?.faceCount ?: 0,
                updatedAtEpochMs = now,
            )
        membershipDao.upsert(row)
    }

    companion object {
        fun normalizeAlias(alias: String): String = alias.trim().lowercase(Locale.ROOT)
    }
}
