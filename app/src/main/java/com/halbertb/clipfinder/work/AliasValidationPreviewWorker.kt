package com.halbertb.clipfinder.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.halbertb.clipfinder.ClipFinderApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AliasValidationPreviewWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    private val app = appContext as ClipFinderApp

    override suspend fun doWork(): Result {
        val aliasId = inputData.getLong(KEY_ALIAS_ID, -1L)
        if (aliasId <= 0) return Result.failure()
        return try {
            val sampleSize = inputData.getInt(KEY_SAMPLE_SIZE, DEFAULT_SAMPLE_SIZE).coerceAtLeast(20)
            val media = withContext(Dispatchers.IO) { app.galleryRepository.listAllImages() }
            val sample = media.shuffled().take(sampleSize)
            val processed = app.personAliasService.runPreview(aliasId = aliasId, sample = sample, provenance = "preview")
            Result.success(workDataOf(KEY_PROCESSED to processed))
        } catch (_: Throwable) {
            Result.failure()
        }
    }

    companion object {
        const val WORK_NAME_PREFIX = "clipfinder_alias_preview_"
        const val KEY_ALIAS_ID = "alias_id"
        const val KEY_SAMPLE_SIZE = "sample_size"
        const val KEY_PROCESSED = "processed"
        private const val DEFAULT_SAMPLE_SIZE = 150
    }
}
