package com.halbertb.clipfinder.domain

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.halbertb.clipfinder.data.db.AliasPhotoMembershipEntity
import com.halbertb.clipfinder.data.db.AliasRefinementStateEntity
import com.halbertb.clipfinder.data.media.GalleryMedia
import com.halbertb.clipfinder.data.person.PersonAliasRepository
import com.halbertb.clipfinder.ml.face.FaceAliasMatcher
import com.halbertb.clipfinder.ml.face.FaceEmbeddingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.halbertb.clipfinder.data.person.PersonAliasRepository.Companion.CENTROID_SOURCE_URI
import java.io.File

class PersonAliasService(
    private val context: Context,
    private val repository: PersonAliasRepository,
    private val faceEmbeddingEngine: FaceEmbeddingEngine,
) {
    data class RefinementChunkResult(
        val processedCount: Int,
        val totalCount: Int,
        val completed: Boolean,
    )

    private val matcher = FaceAliasMatcher(context)
    private val faceCacheVersion = 3
    private val refinementBatchSize = 50
    private val checkpointInterval = 25
    private val progressInterval = 10

    suspend fun listAliases() = repository.listAliases()
    suspend fun getAliasReferenceBundle(aliasId: Long) = repository.getAliasReferenceBundle(aliasId)

    suspend fun resolveAlias(normalizedAlias: String) = repository.getAliasByNormalized(normalizedAlias)

    suspend fun getAlias(aliasId: Long) = repository.getAliasById(aliasId)

    suspend fun deleteAlias(aliasId: Long) = repository.deleteAlias(aliasId)

    suspend fun setMatchThreshold(aliasId: Long, threshold: Float) {
        repository.setAliasMatchThreshold(aliasId, threshold.coerceIn(0.05f, 0.99f))
    }

    suspend fun getMatchThreshold(aliasId: Long): Float =
        repository.getAliasById(aliasId)?.matchThreshold ?: 0.40f

    suspend fun createAliasWithReferences(alias: String, sourceUris: List<Uri>): Long = withContext(Dispatchers.Default) {
        require(sourceUris.isNotEmpty()) { "Pick at least one reference photo." }
        val created = repository.createAlias(alias)
        val references = matcher.buildReferenceEmbeddings(sourceUris, faceEmbeddingEngine)
        require(references.isNotEmpty()) { "No face found in selected photos." }
        val centroid = matcher.buildCentroid(references.map { it.first })
        val rows = references.map { it.first to it.second } + listOfNotNull(centroid?.let { it to Uri.parse(CENTROID_SOURCE_URI) })
        repository.upsertAliasReferences(created.aliasId, rows)
        created.aliasId
    }

    suspend fun runPreview(
        aliasId: Long,
        sample: List<GalleryMedia>,
        provenance: String = "preview",
    ): Int = processMediaForAlias(aliasId, sample, provenance)

    suspend fun runIncrementalUpdate(aliasIds: List<Long>, media: List<GalleryMedia>, provenance: String = "incremental"): Int {
        var total = 0
        for (aliasId in aliasIds) {
            total += processMediaForAlias(aliasId, media, provenance)
        }
        return total
    }

    suspend fun runFullRefinement(aliasId: Long, media: List<GalleryMedia>, onProgress: suspend (Int, Int) -> Unit) {
        val total = media.size
        repository.updateRefinementState(
            AliasRefinementStateEntity(
                aliasId = aliasId,
                lastProcessedMediaId = null,
                processedCount = 0,
                totalCount = total,
                running = true,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
        try {
            val refs = repository.getAliasReferenceBundle(aliasId)
            val threshold = getMatchThreshold(aliasId)
            var done = 0
            for (item in media) {
                val matched = matchWithCache(item, refs, threshold)
                repository.upsertMemberships(
                    listOf(
                        AliasPhotoMembershipEntity(
                            aliasId = aliasId,
                            mediaId = item.id,
                            confidence = matched.confidence,
                            status = matched.status,
                            provenance = "full_refinement",
                            faceCount = matched.faceCount,
                            updatedAtEpochMs = System.currentTimeMillis(),
                        ),
                    ),
                )
                done++
                repository.updateRefinementState(
                    AliasRefinementStateEntity(
                        aliasId = aliasId,
                        lastProcessedMediaId = item.id,
                        processedCount = done,
                        totalCount = total,
                        running = true,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
                onProgress(done, total)
            }
        } finally {
            repository.updateRefinementState(
                AliasRefinementStateEntity(
                    aliasId = aliasId,
                    lastProcessedMediaId = media.lastOrNull()?.id,
                    processedCount = total,
                    totalCount = total,
                    running = false,
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    suspend fun runFullRefinementChunk(
        aliasId: Long,
        media: List<GalleryMedia>,
        maxItems: Int,
        onProgress: suspend (Int, Int) -> Unit,
    ): RefinementChunkResult {
        val total = media.size
        if (total == 0) {
            repository.updateRefinementState(
                AliasRefinementStateEntity(
                    aliasId = aliasId,
                    lastProcessedMediaId = null,
                    processedCount = 0,
                    totalCount = 0,
                    running = false,
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
            return RefinementChunkResult(0, 0, completed = true)
        }

        val state = repository.getRefinementState(aliasId)
        val startIndex =
            state?.lastProcessedMediaId?.let { lastId ->
                val idx = media.indexOfFirst { it.id == lastId }
                if (idx >= 0) idx + 1 else 0
            } ?: 0
        val processedBefore = startIndex.coerceAtMost(total)
        repository.updateRefinementState(
            AliasRefinementStateEntity(
                aliasId = aliasId,
                lastProcessedMediaId = if (processedBefore > 0) media[processedBefore - 1].id else null,
                processedCount = processedBefore,
                totalCount = total,
                running = true,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )

        val endExclusive = (processedBefore + maxItems).coerceAtMost(total)
        val refs = repository.getAliasReferenceBundle(aliasId)
        if (refs.references.isEmpty() && refs.centroid == null) {
            repository.updateRefinementState(
                AliasRefinementStateEntity(
                    aliasId = aliasId,
                    lastProcessedMediaId = if (processedBefore > 0) media[processedBefore - 1].id else null,
                    processedCount = processedBefore,
                    totalCount = total,
                    running = false,
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
            return RefinementChunkResult(processedBefore, total, completed = true)
        }
        val threshold = getMatchThreshold(aliasId)
        var processed = processedBefore
        val pendingRows = ArrayList<AliasPhotoMembershipEntity>(refinementBatchSize)
        try {
            for (index in processedBefore until endExclusive) {
                val item = media[index]
                val matched =
                    try {
                        matchWithCache(item, refs, threshold)
                    } catch (_: Throwable) {
                        // Treat as no faces detected. Cache an empty embedding list so
                        // future runs skip this image without re-attempting decode/detect.
                        runCatching {
                            repository.upsertFaceEmbeddingCache(
                                item.id,
                                item.dateModifiedSec,
                                faceCacheVersion,
                                emptyList(),
                            )
                        }
                        com.halbertb.clipfinder.ml.face.FaceMatchResult(
                            confidence = 0f,
                            faceCount = 0,
                            status = "error",
                        )
                    }
                pendingRows.add(
                    AliasPhotoMembershipEntity(
                        aliasId = aliasId,
                        mediaId = item.id,
                        confidence = matched.confidence,
                        status = matched.status,
                        provenance = "full_refinement",
                        faceCount = matched.faceCount,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
                processed++
                if (pendingRows.size >= refinementBatchSize) {
                    repository.upsertMemberships(pendingRows.toList())
                    pendingRows.clear()
                }
                if (processed % checkpointInterval == 0 || processed == endExclusive) {
                    repository.updateRefinementState(
                        AliasRefinementStateEntity(
                            aliasId = aliasId,
                            lastProcessedMediaId = item.id,
                            processedCount = processed,
                            totalCount = total,
                            running = true,
                            updatedAtEpochMs = System.currentTimeMillis(),
                        ),
                    )
                }
                if (processed % progressInterval == 0 || processed == endExclusive) {
                    onProgress(processed, total)
                }
            }
        } finally {
            // Always flush any pending writes and persist the latest checkpoint, even
            // if the chunk is interrupted (e.g. cancellation), so resume always moves
            // forward.
            if (pendingRows.isNotEmpty()) {
                runCatching { repository.upsertMemberships(pendingRows.toList()) }
                pendingRows.clear()
            }
            if (processed > processedBefore) {
                runCatching {
                    repository.updateRefinementState(
                        AliasRefinementStateEntity(
                            aliasId = aliasId,
                            lastProcessedMediaId = media[processed - 1].id,
                            processedCount = processed,
                            totalCount = total,
                            running = processed < total,
                            updatedAtEpochMs = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }
        val completed = processed >= total
        return RefinementChunkResult(processed, total, completed = completed)
    }

    suspend fun markFeedback(aliasId: Long, mediaId: Long, accepted: Boolean) = repository.markFeedback(aliasId, mediaId, accepted)

    suspend fun matchedMediaIds(aliasId: Long): Set<Long> = repository.listMatchedMediaIds(aliasId).toSet()

    suspend fun matchedMediaConfidences(aliasId: Long): Map<Long, Float> =
        repository.listMatchedMediaConfidences(aliasId).associate { it.mediaId to it.confidence }

    suspend fun getMembershipConfidence(aliasId: Long, mediaId: Long): Float? =
        repository.getConfidence(aliasId, mediaId)

    suspend fun listMembership(aliasId: Long) = repository.listMembership(aliasId)

    suspend fun listPendingPreview(aliasId: Long, limit: Int) = repository.listPendingPreview(aliasId, limit)

    suspend fun getMembershipCounts(aliasId: Long) = repository.getMembershipCounts(aliasId)

    suspend fun getRefinementState(aliasId: Long) = repository.getRefinementState(aliasId)

    suspend fun removeDeletedMemberships(mediaIds: List<Long>) = repository.removeMembershipForDeletedMedia(mediaIds)

    suspend fun removeDeletedFaceEmbeddingCache(mediaIds: List<Long>) = repository.removeFaceEmbeddingCacheForDeletedMedia(mediaIds)

    private suspend fun processMediaForAlias(aliasId: Long, media: List<GalleryMedia>, provenance: String): Int {
        if (media.isEmpty()) return 0
        val refs = repository.getAliasReferenceBundle(aliasId)
        if (refs.references.isEmpty() && refs.centroid == null) return 0
        val threshold = getMatchThreshold(aliasId)
        val rows = ArrayList<AliasPhotoMembershipEntity>(media.size)
        media.forEach { item ->
            val matched = matchWithCache(item, refs, threshold)
            rows.add(
                AliasPhotoMembershipEntity(
                    aliasId = aliasId,
                    mediaId = item.id,
                    confidence = matched.confidence,
                    status = matched.status,
                    provenance = provenance,
                    faceCount = matched.faceCount,
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
        }
        rows.chunked(refinementBatchSize).forEach { repository.upsertMemberships(it) }
        return rows.size
    }

    private suspend fun matchWithCache(
        media: GalleryMedia,
        aliasReferenceBundle: com.halbertb.clipfinder.ml.face.AliasReferenceBundle,
        threshold: Float,
    ): com.halbertb.clipfinder.ml.face.FaceMatchResult {
        val faceEmbeddings =
            repository.getFaceEmbeddingCache(media.id, media.dateModifiedSec, faceCacheVersion)
                ?: run {
                    val extracted = matcher.extractFaceEmbeddings(media.contentUri, faceEmbeddingEngine)
                    repository.upsertFaceEmbeddingCache(media.id, media.dateModifiedSec, faceCacheVersion, extracted)
                    extracted
                }
        return matcher.scoreAliasMatch(
            faceEmbeddings = faceEmbeddings,
            aliasReferenceBundle = aliasReferenceBundle,
            strongThreshold = threshold,
            weakThreshold = (threshold - 0.07f).coerceAtLeast(0.05f),
        )
    }

    /**
     * Recompute alias memberships from the existing face embedding cache only
     * (no decode, no ML Kit). Fast path used when the user adjusts the match threshold.
     * Photos with no cached faces are skipped — they will be recomputed by the
     * normal refinement worker if it runs.
     */
    suspend fun reclassifyFromCache(
        aliasId: Long,
        media: List<GalleryMedia>,
        onProgress: suspend (Int, Int) -> Unit,
    ): Int = withContext(Dispatchers.Default) {
        if (media.isEmpty()) return@withContext 0
        val refs = repository.getAliasReferenceBundle(aliasId)
        if (refs.references.isEmpty() && refs.centroid == null) return@withContext 0
        val threshold = getMatchThreshold(aliasId)
        val weak = (threshold - 0.07f).coerceAtLeast(0.05f)
        val pending = ArrayList<AliasPhotoMembershipEntity>(refinementBatchSize)
        var written = 0
        var processed = 0
        for (item in media) {
            val cached = repository.getFaceEmbeddingCache(item.id, item.dateModifiedSec, faceCacheVersion)
            if (cached != null) {
                val matched = matcher.scoreAliasMatch(
                    faceEmbeddings = cached,
                    aliasReferenceBundle = refs,
                    strongThreshold = threshold,
                    weakThreshold = weak,
                )
                pending.add(
                    AliasPhotoMembershipEntity(
                        aliasId = aliasId,
                        mediaId = item.id,
                        confidence = matched.confidence,
                        status = matched.status,
                        provenance = "reclassify",
                        faceCount = matched.faceCount,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
                if (pending.size >= refinementBatchSize) {
                    repository.upsertMemberships(pending.toList())
                    written += pending.size
                    pending.clear()
                }
            }
            processed++
            if (processed % 200 == 0 || processed == media.size) {
                onProgress(processed, media.size)
            }
        }
        if (pending.isNotEmpty()) {
            repository.upsertMemberships(pending.toList())
            written += pending.size
        }
        written
    }

    suspend fun migrateFacePipelineIfNeeded(
        prefs: SharedPreferences,
        onStatus: (String) -> Unit = {},
    ) {
        val migrated = prefs.getInt(PREF_FACE_MODEL_MIGRATED_VERSION, 0)
        if (migrated >= 4) return
        repository.deleteAllAliasData()
        runCatching { File(context.filesDir, "models/w600k_mbf.onnx").delete() }
        prefs.edit().putInt(PREF_FACE_MODEL_MIGRATED_VERSION, 4).apply()
        onStatus("Face pipeline updated. Old cached face embeddings were cleared; please re-create aliases.")
    }

    companion object {
        const val PREF_FACE_MODEL_MIGRATED_VERSION = "face_model_version_migrated"
    }
}
