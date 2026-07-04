package com.halbertb.clipfinder.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.halbertb.clipfinder.ClipFinderApp
import com.halbertb.clipfinder.domain.CompressedIndexStore
import com.halbertb.clipfinder.domain.SearchCompressionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CompressDatabaseWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    private val app = applicationContext as ClipFinderApp
    private val embeddingDao = app.database.imageEmbeddingDao()
    private val store =
        CompressedIndexStore(
            context = app,
            manifestDao = app.database.compressedIndexManifestDao(),
            memberDao = app.database.compressedIndexMemberDao(),
            embeddingDao = embeddingDao,
        )

    override suspend fun doWork(): Result {
        val modePref = inputData.getString(KEY_MODE_PREF) ?: return Result.failure()
        val deleteFloats = inputData.getBoolean(KEY_DELETE_FLOATS, false)
        val mode = SearchCompressionMode.fromPref(modePref)
        if (mode == SearchCompressionMode.FULL) {
            return Result.failure(
                workDataOf(KEY_ERROR to "Select a compressed search mode first."),
            )
        }

        return try {
            setProgress(workDataOf(KEY_PROGRESS to "Loading float embeddings…"))
            val rows = withContext(Dispatchers.IO) { embeddingDao.getAll() }
            if (rows.isEmpty()) {
                return Result.failure(
                    workDataOf(KEY_ERROR to "No float embeddings found. Scan photos first."),
                )
            }

            setProgress(workDataOf(KEY_PROGRESS to "Building ${mode.title} index…"))
            val buildResult =
                withContext(Dispatchers.Default) {
                    store.buildFromFloatRows(
                        mode = mode,
                        rows = rows,
                        deleteFloats = deleteFloats,
                    )
                }

            val result =
                buildResult.getOrElse { error ->
                    return Result.failure(
                        workDataOf(KEY_ERROR to (error.message ?: "Compress failed")),
                    )
                }

            Result.success(
                workDataOf(
                    KEY_VECTOR_COUNT to result.vectorCount,
                    KEY_FLOATS_REMOVED to result.floatsRemoved,
                    KEY_MODE_PREF to result.mode.prefValue,
                    KEY_FLOAT_TABLE_BYTES to result.storage.floatTableBytes,
                    KEY_COMPRESSED_INDEX_BYTES to result.storage.compressedIndexBytes,
                ),
            )
        } catch (t: Throwable) {
            Result.failure(
                workDataOf(KEY_ERROR to (t.message ?: "Compress failed")),
            )
        }
    }

    companion object {
        const val WORK_NAME = "clipfinder_compress_database"
        const val KEY_MODE_PREF = "mode_pref"
        const val KEY_DELETE_FLOATS = "delete_floats"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
        const val KEY_VECTOR_COUNT = "vector_count"
        const val KEY_FLOATS_REMOVED = "floats_removed"
        const val KEY_FLOAT_TABLE_BYTES = "float_table_bytes"
        const val KEY_COMPRESSED_INDEX_BYTES = "compressed_index_bytes"
    }
}
