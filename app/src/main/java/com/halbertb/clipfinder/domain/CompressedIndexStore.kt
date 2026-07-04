package com.halbertb.clipfinder.domain

import android.content.Context
import com.halbertb.clipfinder.data.db.CompressedIndexManifestEntity
import com.halbertb.clipfinder.data.db.CompressedIndexMemberEntity
import com.halbertb.clipfinder.data.db.CompressedIndexManifestDao
import com.halbertb.clipfinder.data.db.CompressedIndexMemberDao
import com.halbertb.clipfinder.data.db.ImageEmbeddingDao
import com.halbertb.clipfinder.data.db.ImageEmbeddingEntity
import com.halbertb.clipfinder.domain.compression.NativeHighBitVec
import com.halbertb.clipfinder.domain.compression.NativeTurboVecIndex
import com.halbertb.clipfinder.ml.littleEndianBytesToFloatArray
import java.io.File

data class CompressedIndexBuildResult(
    val mode: SearchCompressionMode,
    val vectorCount: Int,
    val filePath: String,
    val floatsRemoved: Boolean,
    val storage: IndexStorageBreakdown,
)

class CompressedIndexStore(
    private val context: Context,
    private val manifestDao: CompressedIndexManifestDao,
    private val memberDao: CompressedIndexMemberDao,
    private val embeddingDao: ImageEmbeddingDao,
) {
    suspend fun getManifest(): CompressedIndexManifestEntity? = manifestDao.get()

    suspend fun indexedCount(): Long {
        val manifest = manifestDao.get()
        if (manifest != null) {
            return manifest.vectorCount.toLong()
        }
        return embeddingDao.count()
    }

    suspend fun hasFloatEmbeddings(): Boolean = embeddingDao.count() > 0

    suspend fun isCompressedOnly(): Boolean {
        val manifest = manifestDao.get() ?: return false
        return manifest.floatsRemoved
    }

    fun indexFileFor(mode: SearchCompressionMode): File =
        when (mode) {
            SearchCompressionMode.TURBOVEC_4BIT -> turboVecFile(context)
            SearchCompressionMode.TURBOQUANT_8BIT -> highBitFile(context)
            SearchCompressionMode.FULL -> error("Full precision has no compressed index file")
        }

    suspend fun buildFromFloatRows(
        mode: SearchCompressionMode,
        rows: List<ImageEmbeddingEntity>,
        deleteFloats: Boolean,
    ): Result<CompressedIndexBuildResult> {
        if (rows.isEmpty()) {
            return Result.failure(IllegalStateException("No float embeddings to compress"))
        }
        val packed = packRows(rows)
        val members =
            rows.map { row ->
                CompressedIndexMemberEntity(
                    mediaId = row.mediaId,
                    dateModifiedSec = row.dateModifiedSec,
                )
            }
        return buildFromPackedVectors(
            mode = mode,
            mediaIds = packed.mediaIds,
            values = packed.values,
            dimension = packed.dimension,
            members = members,
            deleteFloats = deleteFloats,
            floatRowsForStorage = rows,
        )
    }

    suspend fun buildFromPackedVectors(
        mode: SearchCompressionMode,
        mediaIds: LongArray,
        values: FloatArray,
        dimension: Int,
        members: List<CompressedIndexMemberEntity>,
        deleteFloats: Boolean,
        floatRowsForStorage: List<ImageEmbeddingEntity>? = null,
    ): Result<CompressedIndexBuildResult> {
        if (mode == SearchCompressionMode.FULL) {
            return Result.failure(IllegalArgumentException("Cannot compress with full precision mode"))
        }
        if (mediaIds.isEmpty()) {
            return Result.failure(IllegalStateException("No vectors to compress"))
        }
        return runCatching {
            val indexDir = indexDirectory(context)
            indexDir.mkdirs()
            val targetFile = indexFileFor(mode)
            val tempFile = File(indexDir, targetFile.name + ".tmp")

            val vectorCount =
                when (mode) {
                    SearchCompressionMode.TURBOVEC_4BIT -> {
                        NativeTurboVecIndex.create(
                            vectors = values,
                            mediaIds = mediaIds,
                            dim = dimension,
                            bitWidth = 4,
                        )?.use { index ->
                            if (!index.write(tempFile.absolutePath)) {
                                throw IllegalStateException(
                                    NativeTurboVecIndex.lastFailure ?: "TurboVec write failed",
                                )
                            }
                            index.vectorCount
                        }
                            ?: throw IllegalStateException(
                                NativeTurboVecIndex.lastFailure ?: "Could not build 4-bit TurboVec index",
                            )
                    }
                    SearchCompressionMode.TURBOQUANT_8BIT -> {
                        NativeHighBitVec.create(
                            vectors = values,
                            mediaIds = mediaIds,
                            dim = dimension,
                            bits = 8,
                        )?.use { index ->
                            if (!index.write(tempFile.absolutePath)) {
                                throw IllegalStateException(
                                    NativeHighBitVec.lastFailure ?: "TurboQuant write failed",
                                )
                            }
                            index.vectorCount
                        }
                            ?: throw IllegalStateException(
                                NativeHighBitVec.lastFailure ?: "Could not build 8-bit TurboQuant index",
                            )
                    }
                    SearchCompressionMode.FULL -> error("unreachable")
                }

            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            memberDao.clear()
            if (members.isNotEmpty()) {
                memberDao.upsertAll(members)
            }
            manifestDao.upsert(
                CompressedIndexManifestEntity(
                    modePref = mode.prefValue,
                    filePath = targetFile.absolutePath,
                    dimension = dimension,
                    vectorCount = vectorCount,
                    builtAtEpochMs = System.currentTimeMillis(),
                    floatsRemoved = deleteFloats,
                ),
            )
            if (deleteFloats) {
                embeddingDao.deleteAll()
            }
            val compressedIndexBytes = targetFile.length()
            val storage =
                if (floatRowsForStorage != null) {
                    IndexStorageBreakdown.fromFloatRows(
                        rows = floatRowsForStorage,
                        compressedIndexBytes = compressedIndexBytes,
                    )
                } else {
                    IndexStorageBreakdown(
                        floatTableBytes = 0L,
                        compressedIndexBytes = compressedIndexBytes,
                        vectorCount = vectorCount,
                    )
                }
            CompressedIndexBuildResult(
                mode = mode,
                vectorCount = vectorCount,
                filePath = targetFile.absolutePath,
                floatsRemoved = deleteFloats,
                storage = storage,
            )
        }
    }

    suspend fun markFloatsRemoved() {
        val manifest = manifestDao.get() ?: return
        manifestDao.upsert(manifest.copy(floatsRemoved = true))
        embeddingDao.deleteAll()
    }

    suspend fun clearIndex() {
        manifestDao.get()?.filePath?.let { path ->
            runCatching { File(path).delete() }
        }
        manifestDao.clear()
        memberDao.clear()
    }

    suspend fun syncMembersFromRows(rows: List<ImageEmbeddingEntity>) {
        memberDao.clear()
        if (rows.isNotEmpty()) {
            memberDao.upsertAll(
                rows.map { row ->
                    CompressedIndexMemberEntity(
                        mediaId = row.mediaId,
                        dateModifiedSec = row.dateModifiedSec,
                    )
                },
            )
        }
    }

    suspend fun updateManifestVectorCount(count: Int) {
        val manifest = manifestDao.get() ?: return
        manifestDao.upsert(
            manifest.copy(
                vectorCount = count,
                builtAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    private data class PackedRows(
        val mediaIds: LongArray,
        val values: FloatArray,
        val dimension: Int,
    )

    private fun packRows(rows: List<ImageEmbeddingEntity>): PackedRows {
        val dim = littleEndianBytesToFloatArray(rows.first().embedding).size
        val mediaIds = LongArray(rows.size)
        val values = FloatArray(rows.size * dim)
        for (row in rows.indices) {
            mediaIds[row] = rows[row].mediaId
            val vector = littleEndianBytesToFloatArray(rows[row].embedding)
            require(vector.size == dim) { "Embedding dimension mismatch in indexed gallery." }
            vector.copyInto(values, destinationOffset = row * dim)
        }
        return PackedRows(mediaIds = mediaIds, values = values, dimension = dim)
    }

    companion object {
        private const val INDEX_DIR = "compressed_index"

        fun indexDirectory(context: Context): File = File(context.filesDir, INDEX_DIR)

        fun turboVecFile(context: Context): File = File(indexDirectory(context), "turbovec_4.tvim")

        fun highBitFile(context: Context): File = File(indexDirectory(context), "turboquant_8.hbvq")

        fun packRowsPublic(rows: List<ImageEmbeddingEntity>): Triple<LongArray, FloatArray, Int> {
            if (rows.isEmpty()) {
                return Triple(LongArray(0), FloatArray(0), 0)
            }
            val dim = littleEndianBytesToFloatArray(rows.first().embedding).size
            val mediaIds = LongArray(rows.size)
            val values = FloatArray(rows.size * dim)
            for (row in rows.indices) {
                mediaIds[row] = rows[row].mediaId
                val vector = littleEndianBytesToFloatArray(rows[row].embedding)
                require(vector.size == dim) { "Embedding dimension mismatch in indexed gallery." }
                vector.copyInto(values, destinationOffset = row * dim)
            }
            return Triple(mediaIds, values, dim)
        }
    }
}
