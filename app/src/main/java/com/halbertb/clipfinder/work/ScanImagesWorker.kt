package com.halbertb.clipfinder.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.halbertb.clipfinder.ClipFinderApp
import com.halbertb.clipfinder.data.db.ImageEmbeddingEntity
import com.halbertb.clipfinder.ml.clip.ClipOnnxEngine
import com.halbertb.clipfinder.ml.floatArrayToLittleEndianBytes
import com.halbertb.clipfinder.util.decodeBitmapForClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ScanImagesWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    private val app = applicationContext as ClipFinderApp
    private val dao = app.database.imageEmbeddingDao()
    private val gallery = app.galleryRepository
    private val modelStore = app.modelStore

    override suspend fun doWork(): Result {
        var clip: ClipOnnxEngine? = null
        return try {
            if (!modelStore.modelsReady()) {
                return Result.failure()
            }
            showProgressNotification(0, 0, indeterminate = true)
            clip =
                ClipOnnxEngine(
                    visionModelFile = modelStore.visionFile,
                    textModelFile = modelStore.textFile,
                )
            val all = withContext(Dispatchers.IO) { gallery.listAllImages() }
            setProgress(workDataOf(KEY_PROGRESS_DONE to 0, KEY_PROGRESS_TOTAL to all.size))
            showProgressNotification(0, all.size, indeterminate = false)
            val galleryIds = all.map { it.id }.toHashSet()
            val storedIds = withContext(Dispatchers.IO) { dao.getAllMediaIds() }
            val orphans = storedIds.filter { it !in galleryIds }
            withContext(Dispatchers.IO) {
                orphans.chunked(450).forEach { chunk ->
                    if (chunk.isNotEmpty()) dao.deleteByMediaIds(chunk)
                }
            }

            var processed = 0
            var indexedNow = 0
            var skippedUnchanged = 0
            var decodeFailures = 0

            for (media in all) {
                if (isStopped) return Result.failure()
                val existing = withContext(Dispatchers.IO) { dao.getById(media.id) }
                if (existing != null && existing.dateModifiedSec == media.dateModifiedSec) {
                    skippedUnchanged++
                    processed++
                    setProgress(workDataOf(KEY_PROGRESS_DONE to processed, KEY_PROGRESS_TOTAL to all.size))
                    showProgressNotification(processed, all.size, indeterminate = false)
                    continue
                }

                val bmp = withContext(Dispatchers.IO) { decodeBitmapForClip(app, media.contentUri) }
                if (bmp != null) {
                    val emb = withContext(Dispatchers.Default) { clip.encodeImage(bmp) }
                    recycleQuietly(bmp)
                    val row =
                        ImageEmbeddingEntity(
                            mediaId = media.id,
                            dateModifiedSec = media.dateModifiedSec,
                            embedding = floatArrayToLittleEndianBytes(emb),
                            indexedAtEpochMs = System.currentTimeMillis(),
                        )
                    withContext(Dispatchers.IO) { dao.upsert(row) }
                    indexedNow++
                } else {
                    decodeFailures++
                }

                processed++
                setProgress(workDataOf(KEY_PROGRESS_DONE to processed, KEY_PROGRESS_TOTAL to all.size))
                showProgressNotification(processed, all.size, indeterminate = false)
            }

            val count = withContext(Dispatchers.IO) { dao.count() }
            showCompletionNotification(
                success = true,
                message = "Scan finished. Indexed $indexedNow, unchanged $skippedUnchanged, failures $decodeFailures.",
            )
            Result.success(
                androidx.work.workDataOf(
                    KEY_INDEXED_NOW to indexedNow,
                    KEY_SKIPPED_UNCHANGED to skippedUnchanged,
                    KEY_DECODE_FAILURES to decodeFailures,
                    KEY_TOTAL_INDEXED to count.toInt(),
                    KEY_REMOVED_STALE to orphans.size,
                ),
            )
        } catch (_: Throwable) {
            showCompletionNotification(success = false, message = "Background scan failed.")
            Result.failure()
        } finally {
            clip?.close()
        }
    }

    private fun recycleQuietly(bitmap: Bitmap) {
        if (!bitmap.isRecycled) bitmap.recycle()
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
                "CLIP Finder background scan",
                NotificationManager.IMPORTANCE_LOW,
            )
        notificationManager().createNotificationChannel(channel)
    }

    private fun showProgressNotification(done: Int, total: Int, indeterminate: Boolean) {
        if (!canPostNotifications()) return
        ensureNotificationChannel()
        val builder =
            NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("CLIP Finder scanning")
                .setContentText(
                    if (indeterminate || total <= 0) "Scanning photos…" else "Processed $done / $total",
                )
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setProgress(total.coerceAtLeast(0), done.coerceAtLeast(0), indeterminate || total <= 0)
        notificationManager().notify(NOTIFICATION_ID_PROGRESS, builder.build())
    }

    private fun showCompletionNotification(success: Boolean, message: String) {
        if (!canPostNotifications()) return
        ensureNotificationChannel()
        notificationManager().cancel(NOTIFICATION_ID_PROGRESS)
        val builder =
            NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(
                    if (success) android.R.drawable.stat_notify_more else android.R.drawable.stat_notify_error,
                )
                .setContentTitle(if (success) "CLIP Finder scan complete" else "CLIP Finder scan failed")
                .setContentText(message)
                .setAutoCancel(true)
                .setOngoing(false)
        notificationManager().notify(NOTIFICATION_ID_COMPLETION, builder.build())
    }

    companion object {
        const val WORK_NAME = "clipfinder_scan_images"
        const val KEY_INDEXED_NOW = "indexed_now"
        const val KEY_SKIPPED_UNCHANGED = "skipped_unchanged"
        const val KEY_DECODE_FAILURES = "decode_failures"
        const val KEY_TOTAL_INDEXED = "total_indexed"
        const val KEY_REMOVED_STALE = "removed_stale"
        const val KEY_PROGRESS_DONE = "progress_done"
        const val KEY_PROGRESS_TOTAL = "progress_total"
        private const val CHANNEL_ID = "clipfinder_scan_channel"
        private const val NOTIFICATION_ID_PROGRESS = 41001
        private const val NOTIFICATION_ID_COMPLETION = 41002
    }
}

