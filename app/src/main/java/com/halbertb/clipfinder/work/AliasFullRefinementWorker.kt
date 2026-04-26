package com.halbertb.clipfinder.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.halbertb.clipfinder.ClipFinderApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AliasFullRefinementWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    private val app = appContext as ClipFinderApp

    override suspend fun doWork(): Result {
        val aliasId = inputData.getLong(KEY_ALIAS_ID, -1L)
        if (aliasId <= 0) return Result.failure()
        return try {
            val alias = app.personAliasService.getAlias(aliasId)
            if (alias == null) return Result.success()
            showProgressNotification(0, 0, indeterminate = true)
            val media = withContext(Dispatchers.IO) { app.galleryRepository.listAllImages() }
            val savedState = app.personAliasService.getRefinementState(aliasId)
            val initialDone = (savedState?.processedCount ?: 0).coerceIn(0, media.size)
            val totalAtStart = media.size
            showProgressNotification(initialDone, totalAtStart, indeterminate = false)
            setProgress(
                workDataOf(
                    KEY_PROGRESS_DONE to initialDone,
                    KEY_PROGRESS_TOTAL to totalAtStart,
                ),
            )

            val deadline = System.currentTimeMillis() + RUN_BUDGET_MS
            var lastResult =
                AliasChunkOutcome(
                    processedCount = initialDone,
                    totalCount = totalAtStart,
                    completed = totalAtStart == 0,
                )

            while (true) {
                if (isStopped) {
                    return Result.success(
                        workDataOf(
                            KEY_PROGRESS_DONE to lastResult.processedCount,
                            KEY_PROGRESS_TOTAL to lastResult.totalCount,
                            KEY_COMPLETED to lastResult.completed,
                        ),
                    )
                }
                val chunkResult =
                    app.personAliasService.runFullRefinementChunk(aliasId, media, CHUNK_SIZE) { done, total ->
                        setProgress(workDataOf(KEY_PROGRESS_DONE to done, KEY_PROGRESS_TOTAL to total))
                        showProgressNotification(done, total, indeterminate = false)
                    }
                lastResult =
                    AliasChunkOutcome(
                        processedCount = chunkResult.processedCount,
                        totalCount = chunkResult.totalCount,
                        completed = chunkResult.completed,
                    )
                showProgressNotification(
                    lastResult.processedCount,
                    lastResult.totalCount,
                    indeterminate = false,
                )

                if (lastResult.completed) {
                    showCompletionNotification(success = true)
                    return Result.success(
                        workDataOf(
                            KEY_PROGRESS_DONE to lastResult.processedCount,
                            KEY_PROGRESS_TOTAL to lastResult.totalCount,
                            KEY_COMPLETED to true,
                        ),
                    )
                }
                if (System.currentTimeMillis() >= deadline) {
                    return Result.retry()
                }
            }
            @Suppress("UNREACHABLE_CODE")
            Result.retry()
        } catch (_: Throwable) {
            if (runAttemptCount >= MAX_RETRY_ATTEMPTS) {
                showCompletionNotification(success = false)
                return Result.failure()
            }
            Result.retry()
        }
    }

    private data class AliasChunkOutcome(
        val processedCount: Int,
        val totalCount: Int,
        val completed: Boolean,
    )

    private fun buildNotification(
        done: Int,
        total: Int,
        indeterminate: Boolean,
        completed: Boolean,
    ) = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
        .setSmallIcon(if (completed) android.R.drawable.stat_notify_more else android.R.drawable.stat_notify_sync)
        .setContentTitle(
            if (completed) {
                "Alias refinement complete"
            } else {
                "CLIP Finder refining aliases"
            },
        )
        .setContentText(
            if (completed) {
                "Background alias refinement finished."
            } else if (indeterminate || total <= 0) {
                "Refining alias matches…"
            } else {
                "Processed $done / $total photos"
            },
        )
        .setOnlyAlertOnce(true)
        .setOngoing(!completed)
        .setProgress(total.coerceAtLeast(0), done.coerceAtLeast(0), indeterminate || total <= 0)
        .build()

    private fun showProgressNotification(done: Int, total: Int, indeterminate: Boolean) {
        if (!canPostNotifications()) return
        ensureNotificationChannel()
        notificationManager().notify(
            NOTIFICATION_ID_PROGRESS,
            buildNotification(done, total, indeterminate = indeterminate, completed = false),
        )
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun notificationManager(): NotificationManager =
        applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "CLIP Finder alias refinement",
                NotificationManager.IMPORTANCE_LOW,
            )
        notificationManager().createNotificationChannel(channel)
    }

    private fun showCompletionNotification(success: Boolean) {
        if (!canPostNotifications()) return
        ensureNotificationChannel()
        notificationManager().cancel(NOTIFICATION_ID_PROGRESS)
        val builder =
            NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(
                    if (success) android.R.drawable.stat_notify_more else android.R.drawable.stat_notify_error,
                )
                .setContentTitle(if (success) "Alias refinement complete" else "Alias refinement failed")
                .setContentText(
                    if (success) {
                        "Background alias refinement finished."
                    } else {
                        "Background alias refinement failed."
                    },
                )
                .setAutoCancel(true)
                .setOngoing(false)
        notificationManager().notify(NOTIFICATION_ID_COMPLETION, builder.build())
    }

    companion object {
        const val WORK_NAME_PREFIX = "clipfinder_alias_refine_"
        const val KEY_ALIAS_ID = "alias_id"
        const val KEY_PROGRESS_DONE = "progress_done"
        const val KEY_PROGRESS_TOTAL = "progress_total"
        const val KEY_COMPLETED = "completed"

        // Smaller chunks so the worker checks isStopped/deadline frequently and
        // checkpoints the cursor more often. Each chunk batches its DB writes
        // internally via PersonAliasService.runFullRefinementChunk.
        private const val CHUNK_SIZE = 75

        // Stay well below the 10-minute WorkManager run limit so we exit cleanly
        // and let WorkManager re-run via Result.retry() with backoff.
        private const val RUN_BUDGET_MS = 7L * 60L * 1000L

        // Cap automatic retries so a hard failure surfaces a "Resume" button in
        // the UI instead of looping forever in the background.
        private const val MAX_RETRY_ATTEMPTS = 8

        private const val CHANNEL_ID = "clipfinder_alias_refine_channel"
        private const val NOTIFICATION_ID_PROGRESS = 42001
        private const val NOTIFICATION_ID_COMPLETION = 42002
    }
}
