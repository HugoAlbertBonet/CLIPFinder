package com.halbertb.clipfinder.domain

import com.halbertb.clipfinder.data.db.CompressedIndexManifestEntity
import com.halbertb.clipfinder.data.db.ImageEmbeddingEntity
import com.halbertb.clipfinder.domain.compression.NativeHighBitVec
import com.halbertb.clipfinder.domain.compression.NativeTurboVecIndex
import com.halbertb.clipfinder.ml.littleEndianBytesToFloatArray
import java.io.File

class CompressedImageSearch {
    private var cacheKey: String? = null
    private var turboVec4: NativeTurboVecIndex? = null
    private var turboQuant8: NativeHighBitVec? = null

    fun invalidate() {
        cacheKey = null
        turboVec4?.close()
        turboVec4 = null
        turboQuant8?.close()
        turboQuant8 = null
    }

    fun search(
        mode: SearchCompressionMode,
        manifest: CompressedIndexManifestEntity?,
        floatRows: List<ImageEmbeddingEntity>?,
        allowlistMediaIds: LongArray?,
        query: FloatArray,
        k: Int,
    ): CompressedSearchOutcome {
        if (mode == SearchCompressionMode.FULL) {
            return CompressedSearchOutcome.Unsupported
        }

        val usePersisted =
            manifest != null &&
                manifest.modePref == mode.prefValue &&
                File(manifest.filePath).exists()

        if (usePersisted) {
            ensureLoadedFromManifest(mode, manifest!!)
        } else if (!floatRows.isNullOrEmpty()) {
            rebuildFromFloats(mode, floatRows)
        } else {
            return CompressedSearchOutcome.Failed(
                if (manifest != null && manifest.modePref != mode.prefValue) {
                    "Compressed index was built for a different mode. Re-compress or switch mode."
                } else {
                    "No compressed index on disk. Tap Compress database first."
                },
            )
        }

        val limit = k.coerceAtLeast(1)
        val result =
            when (mode) {
                SearchCompressionMode.TURBOVEC_4BIT ->
                    turboVec4?.search(query, limit, allowlistMediaIds)
                        ?: return CompressedSearchOutcome.Failed(
                            NativeTurboVecIndex.lastFailure ?: "4-bit TurboVec index unavailable",
                        )
                SearchCompressionMode.TURBOQUANT_8BIT ->
                    turboQuant8?.search(query, limit, allowlistMediaIds)
                        ?: return CompressedSearchOutcome.Failed(
                            NativeHighBitVec.lastFailure ?: "8-bit TurboQuant index unavailable",
                        )
                SearchCompressionMode.FULL -> return CompressedSearchOutcome.Unsupported
            }
        val items =
            result.items.map { item ->
                ScoredImage(mediaId = item.mediaId, score = item.score)
            }
        return CompressedSearchOutcome.Success(
            items = items,
            searchElapsedMs = result.searchElapsedMs,
        )
    }

    /** Legacy entry point used when only float rows are available. */
    fun search(
        mode: SearchCompressionMode,
        rows: List<ImageEmbeddingEntity>,
        query: FloatArray,
        k: Int,
    ): CompressedSearchOutcome =
        search(
            mode = mode,
            manifest = null,
            floatRows = rows,
            allowlistMediaIds = null,
            query = query,
            k = k,
        )

    private fun ensureLoadedFromManifest(
        mode: SearchCompressionMode,
        manifest: CompressedIndexManifestEntity,
    ) {
        val key = "${manifest.modePref}:${manifest.filePath}:${manifest.vectorCount}:${manifest.builtAtEpochMs}"
        if (key == cacheKey) {
            return
        }
        invalidate()
        when (mode) {
            SearchCompressionMode.TURBOVEC_4BIT -> {
                turboVec4 =
                    NativeTurboVecIndex.load(manifest.filePath)
                        ?: throw IllegalStateException(
                            NativeTurboVecIndex.lastFailure ?: "Could not load 4-bit TurboVec index",
                        )
            }
            SearchCompressionMode.TURBOQUANT_8BIT -> {
                turboQuant8 =
                    NativeHighBitVec.load(manifest.filePath)
                        ?: throw IllegalStateException(
                            NativeHighBitVec.lastFailure ?: "Could not load 8-bit TurboQuant index",
                        )
            }
            SearchCompressionMode.FULL -> Unit
        }
        cacheKey = key
    }

    private fun rebuildFromFloats(
        mode: SearchCompressionMode,
        rows: List<ImageEmbeddingEntity>,
    ) {
        val packed = packRows(rows)
        val key = cacheKeyForFloat(mode, rows)
        if (key == cacheKey) {
            return
        }
        invalidate()
        when (mode) {
            SearchCompressionMode.TURBOVEC_4BIT -> {
                turboVec4 =
                    NativeTurboVecIndex.create(
                        vectors = packed.values,
                        mediaIds = packed.mediaIds,
                        dim = packed.dimension,
                        bitWidth = 4,
                    )
                        ?: throw IllegalStateException(
                            NativeTurboVecIndex.lastFailure ?: "Could not build 4-bit TurboVec index",
                        )
            }
            SearchCompressionMode.TURBOQUANT_8BIT -> {
                turboQuant8 =
                    NativeHighBitVec.create(
                        vectors = packed.values,
                        mediaIds = packed.mediaIds,
                        dim = packed.dimension,
                        bits = 8,
                    )
                        ?: throw IllegalStateException(
                            NativeHighBitVec.lastFailure ?: "Could not build 8-bit TurboQuant index",
                        )
            }
            SearchCompressionMode.FULL -> Unit
        }
        cacheKey = key
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

    private fun cacheKeyForFloat(
        mode: SearchCompressionMode,
        rows: List<ImageEmbeddingEntity>,
    ): String {
        var hash = mode.ordinal.toLong() shl 32
        hash = hash xor rows.size.toLong()
        if (rows.isNotEmpty()) {
            hash = hash xor rows.first().mediaId
            hash = hash xor (rows.last().mediaId shl 1)
        }
        return "${mode.prefValue}:float:$hash"
    }
}

sealed interface CompressedSearchOutcome {
    data object Unsupported : CompressedSearchOutcome

    data class Failed(
        val message: String,
    ) : CompressedSearchOutcome

    data class Success(
        val items: List<ScoredImage>,
        val searchElapsedMs: Long,
    ) : CompressedSearchOutcome
}
