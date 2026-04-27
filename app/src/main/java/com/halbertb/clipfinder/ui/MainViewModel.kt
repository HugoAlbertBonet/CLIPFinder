package com.halbertb.clipfinder.ui

import android.Manifest
import android.app.Application
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.halbertb.clipfinder.ClipFinderApp
import com.halbertb.clipfinder.domain.filterRowsByAllowedMediaIds
import com.halbertb.clipfinder.domain.scoreIndexedImages
import com.halbertb.clipfinder.ml.clip.ClipOnnxEngine
import com.halbertb.clipfinder.ml.clip.ClipTokenizer
import com.halbertb.clipfinder.work.AliasFullRefinementWorker
import com.halbertb.clipfinder.work.AliasValidationPreviewWorker
import com.halbertb.clipfinder.work.ScanImagesWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SearchResultItem(
    val mediaId: Long,
    val score: Float,
    /** CLIP-only score before any alias boost, kept so the preview can show both. */
    val clipScore: Float = score,
    /** Alias face-match confidence for this image, when an alias filter is applied. */
    val aliasConfidence: Float? = null,
)

data class PersonAliasItem(
    val aliasId: Long,
    val alias: String,
)

data class AliasStats(
    val included: Int,
    val notIncluded: Int,
    val errors: Int,
) {
    val processed: Int get() = included + notIncluded + errors
}

data class AliasPreviewItem(
    val mediaId: Long,
    val confidence: Float,
    val status: String,
)

data class MainUiState(
    val statusMessage: String = "",
    val indexedCount: Long = 0,
    val scanDone: Int = 0,
    val scanTotal: Int = 0,
    val scanning: Boolean = false,
    val modelsReady: Boolean = false,
    val positivePrompt: String = "",
    val negativePrompt: String = "",
    val k: Int = 12,
    val searchResults: List<SearchResultItem> = emptyList(),
    val selectedScreen: String = "search",
    val aliasInput: String = "",
    /** Selected alias to filter the search by, or null for no filter. */
    val selectedAliasFilterId: Long? = null,
    /** When true and an alias filter is set, multiply CLIP score by alias face confidence. */
    val boostByAliasConfidence: Boolean = false,
    val aliases: List<PersonAliasItem> = emptyList(),
    val aliasStats: Map<Long, AliasStats> = emptyMap(),
    val selectedAliasIdForManage: Long? = null,
    val aliasPreview: List<AliasPreviewItem> = emptyList(),
    val refinementDone: Int = 0,
    val refinementTotal: Int = 0,
    val refinementRunning: Boolean = false,
    val refinementCanResume: Boolean = false,
    /** Per-alias threshold value [0..1] currently shown in the People tab. */
    val aliasThresholds: Map<Long, Float> = emptyMap(),
    val reclassifying: Boolean = false,
    val reclassifyDone: Int = 0,
    val reclassifyTotal: Int = 0,
    val busy: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ClipFinderApp
    private val dao = app.database.imageEmbeddingDao()
    private val gallery = app.galleryRepository
    private val modelStore = app.modelStore
    private val faceModelStore = app.faceModelStore
    private val promptTranslator = app.promptTranslator
    private val personAliasService = app.personAliasService
    private val workManager = WorkManager.getInstance(app)

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    @Volatile
    private var engine: ClipOnnxEngine? = null

    @Volatile
    private var tokenizer: ClipTokenizer? = null

    init {
        refreshCounts()
        resumeScanMonitorIfNeeded()
        runFaceModelMigrationIfNeeded()
    }

    fun refreshCounts() {
        viewModelScope.launch {
            val ready = modelStore.modelsReady()
            val count = withContext(Dispatchers.IO) { dao.count() }
            syncEngineIfPossible(ready)
            _state.update {
                it.copy(
                    modelsReady = ready,
                    indexedCount = count,
                )
            }
            refreshAliases()
        }
    }

    fun setScreen(screen: String) {
        _state.update { it.copy(selectedScreen = screen) }
    }

    fun setPositive(value: String) {
        _state.update { it.copy(positivePrompt = value) }
    }

    fun setNegative(value: String) {
        _state.update { it.copy(negativePrompt = value) }
    }

    fun setK(value: Int) {
        _state.update { it.copy(k = value.coerceIn(1, 50)) }
    }

    fun setAliasInput(value: String) {
        _state.update { it.copy(aliasInput = value) }
    }

    fun setAliasFilterId(aliasId: Long?) {
        _state.update { it.copy(selectedAliasFilterId = aliasId) }
    }

    fun setBoostByAliasConfidence(enabled: Boolean) {
        _state.update { it.copy(boostByAliasConfidence = enabled) }
    }

    fun setAliasThreshold(aliasId: Long, threshold: Float) {
        _state.update {
            it.copy(aliasThresholds = it.aliasThresholds + (aliasId to threshold.coerceIn(0.05f, 0.99f)))
        }
    }

    /** Persist the threshold to the DB. Call this on slider release. */
    fun commitAliasThreshold(aliasId: Long) {
        val pending = _state.value.aliasThresholds[aliasId] ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { personAliasService.setMatchThreshold(aliasId, pending) }
        }
    }

    fun reclassifyAliasFromCache() {
        val aliasId = _state.value.selectedAliasIdForManage ?: return
        viewModelScope.launch {
            commitAliasThreshold(aliasId)
            _state.update { it.copy(reclassifying = true, reclassifyDone = 0, reclassifyTotal = 0, statusMessage = "Reclassifying with new threshold…") }
            try {
                val media = withContext(Dispatchers.IO) { app.galleryRepository.listAllImages() }
                _state.update { it.copy(reclassifyTotal = media.size) }
                withContext(Dispatchers.IO) {
                    personAliasService.reclassifyFromCache(aliasId, media) { done, total ->
                        _state.update { it.copy(reclassifyDone = done, reclassifyTotal = total) }
                    }
                }
                _state.update { it.copy(statusMessage = "Reclassification complete.") }
            } catch (e: Exception) {
                _state.update { it.copy(statusMessage = "Reclassification failed: ${e.message}") }
            } finally {
                _state.update { it.copy(reclassifying = false) }
                refreshAliasStats(listOf(aliasId))
                loadAliasPreview(aliasId)
            }
        }
    }

    private fun refreshAliases() {
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) { personAliasService.listAliases() }
            _state.update {
                it.copy(
                    aliases = rows.map { a -> PersonAliasItem(aliasId = a.aliasId, alias = a.alias) },
                )
            }
            refreshAliasStats(rows.map { it.aliasId })
        }
    }

    private fun runFaceModelMigrationIfNeeded() {
        viewModelScope.launch {
            val prefs = app.getSharedPreferences("clipfinder_prefs", android.content.Context.MODE_PRIVATE)
            val migrated =
                prefs.getInt(
                    com.halbertb.clipfinder.domain.PersonAliasService.PREF_FACE_MODEL_MIGRATED_VERSION,
                    0,
                )
            if (migrated < 4) {
                val aliases = withContext(Dispatchers.IO) { personAliasService.listAliases() }
                aliases.forEach { alias ->
                    workManager.cancelUniqueWork(AliasFullRefinementWorker.WORK_NAME_PREFIX + alias.aliasId)
                    workManager.cancelUniqueWork(AliasValidationPreviewWorker.WORK_NAME_PREFIX + alias.aliasId)
                }
                withContext(Dispatchers.IO) {
                    personAliasService.migrateFacePipelineIfNeeded(prefs) { msg ->
                        _state.update { s -> s.copy(statusMessage = msg) }
                    }
                }
            }
            refreshAliases()
            resumeAliasMonitorsIfNeeded()
        }
    }

    private fun refreshAliasStats(aliasIds: List<Long>) {
        if (aliasIds.isEmpty()) {
            _state.update { it.copy(aliasStats = emptyMap()) }
            return
        }
        viewModelScope.launch {
            val stats =
                withContext(Dispatchers.IO) {
                    aliasIds.associateWith { id ->
                        val c = personAliasService.getMembershipCounts(id)
                        AliasStats(
                            included = c.includedCount,
                            notIncluded = c.notIncludedCount,
                            errors = c.errorCount,
                        )
                    }
                }
            _state.update { it.copy(aliasStats = stats) }
        }
    }

    private fun refreshAliasStatsForCurrent() {
        val ids = _state.value.aliases.map { it.aliasId }
        if (ids.isNotEmpty()) refreshAliasStats(ids)
    }

    fun selectAliasForManage(aliasId: Long) {
        _state.update { it.copy(selectedAliasIdForManage = aliasId) }
        refreshRefinementState(aliasId)
        loadAliasPreview(aliasId)
        loadAliasThreshold(aliasId)
        monitorAliasRefinement(aliasId)
    }

    private fun loadAliasThreshold(aliasId: Long) {
        viewModelScope.launch {
            val t = withContext(Dispatchers.IO) { personAliasService.getMatchThreshold(aliasId) }
            _state.update {
                if (it.aliasThresholds.containsKey(aliasId)) it
                else it.copy(aliasThresholds = it.aliasThresholds + (aliasId to t))
            }
        }
    }

    fun resumeAliasRefinement() {
        val aliasId = _state.value.selectedAliasIdForManage ?: return
        enqueueAliasRefinement(aliasId)
        _state.update {
            it.copy(
                refinementRunning = true,
                refinementCanResume = false,
                statusMessage = "Resuming alias refinement in background…",
            )
        }
        monitorAliasRefinement(aliasId)
    }

    fun deleteAlias(aliasId: Long) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, statusMessage = "Deleting alias…") }
            try {
                workManager.cancelUniqueWork(AliasValidationPreviewWorker.WORK_NAME_PREFIX + aliasId)
                workManager.cancelUniqueWork(AliasFullRefinementWorker.WORK_NAME_PREFIX + aliasId)
                withContext(Dispatchers.IO) { personAliasService.deleteAlias(aliasId) }
                refreshAliases()
                _state.update {
                    it.copy(
                        selectedAliasIdForManage = if (it.selectedAliasIdForManage == aliasId) null else it.selectedAliasIdForManage,
                        aliasPreview = if (it.selectedAliasIdForManage == aliasId) emptyList() else it.aliasPreview,
                        refinementDone = if (it.selectedAliasIdForManage == aliasId) 0 else it.refinementDone,
                        refinementTotal = if (it.selectedAliasIdForManage == aliasId) 0 else it.refinementTotal,
                        refinementRunning = if (it.selectedAliasIdForManage == aliasId) false else it.refinementRunning,
                        refinementCanResume = if (it.selectedAliasIdForManage == aliasId) false else it.refinementCanResume,
                        statusMessage = "Alias deleted.",
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(statusMessage = "Could not delete alias: ${e.message}") }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun createAliasFromUris(sourceUris: List<Uri>) {
        viewModelScope.launch {
            val alias = _state.value.aliasInput.trim()
            if (alias.isEmpty()) {
                _state.update { it.copy(statusMessage = "Enter an alias name first.") }
                return@launch
            }
            if (sourceUris.isEmpty()) {
                _state.update { it.copy(statusMessage = "Pick at least one example photo.") }
                return@launch
            }
            _state.update { it.copy(busy = true, statusMessage = "Creating alias…") }
            try {
                faceModelStore.ensureReady { msg -> _state.update { s -> s.copy(statusMessage = msg) } }
                val aliasId = personAliasService.createAliasWithReferences(alias, sourceUris)
                enqueueAliasPreview(aliasId)
                enqueueAliasRefinement(aliasId)
                refreshAliases()
                _state.update {
                    it.copy(
                        selectedAliasIdForManage = aliasId,
                        aliasInput = "",
                        statusMessage = "Alias created. Running validation preview and background full refinement. You can close the app.",
                    )
                }
                loadAliasPreview(aliasId)
                monitorAliasRefinement(aliasId)
            } catch (e: Exception) {
                _state.update { it.copy(statusMessage = "Alias creation failed: ${e.message}") }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun enqueueAliasPreview(aliasId: Long) {
        val request =
            OneTimeWorkRequestBuilder<AliasValidationPreviewWorker>()
                .setInputData(
                    androidx.work.workDataOf(
                        AliasValidationPreviewWorker.KEY_ALIAS_ID to aliasId,
                    ),
                ).build()
        workManager.enqueueUniqueWork(
            AliasValidationPreviewWorker.WORK_NAME_PREFIX + aliasId,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private fun enqueueAliasRefinement(aliasId: Long) {
        val request =
            OneTimeWorkRequestBuilder<AliasFullRefinementWorker>()
                .setInputData(
                    androidx.work.workDataOf(
                        AliasFullRefinementWorker.KEY_ALIAS_ID to aliasId,
                    ),
                )
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.LINEAR,
                    15L,
                    java.util.concurrent.TimeUnit.SECONDS,
                )
                .build()
        workManager.enqueueUniqueWork(
            AliasFullRefinementWorker.WORK_NAME_PREFIX + aliasId,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    @Volatile
    private var monitoredAliasId: Long? = null

    private fun monitorAliasRefinement(aliasId: Long) {
        if (monitoredAliasId == aliasId) return
        monitoredAliasId = aliasId
        viewModelScope.launch {
            val workName = AliasFullRefinementWorker.WORK_NAME_PREFIX + aliasId
            try {
                while (true) {
                    val infos = withContext(Dispatchers.IO) { workManager.getWorkInfosForUniqueWork(workName).get() }
                    // Always reconcile against the DB checkpoint so a worker that is
                    // ENQUEUED (waiting on backoff) with empty progress data does not
                    // wipe out the progress bar in the UI.
                    val dbState = withContext(Dispatchers.IO) { personAliasService.getRefinementState(aliasId) }
                    val activeInfo =
                        infos.firstOrNull {
                            it.state == WorkInfo.State.RUNNING ||
                                it.state == WorkInfo.State.ENQUEUED ||
                                it.state == WorkInfo.State.BLOCKED
                        }
                    if (activeInfo != null) {
                        val workDone = activeInfo.progress.getInt(AliasFullRefinementWorker.KEY_PROGRESS_DONE, -1)
                        val workTotal = activeInfo.progress.getInt(AliasFullRefinementWorker.KEY_PROGRESS_TOTAL, -1)
                        val done =
                            when {
                                workDone >= 0 && workTotal > 0 -> workDone
                                dbState != null -> dbState.processedCount
                                else -> _state.value.refinementDone
                            }
                        val total =
                            when {
                                workTotal > 0 -> workTotal
                                dbState != null && dbState.totalCount > 0 -> dbState.totalCount
                                else -> _state.value.refinementTotal
                            }
                        _state.update {
                            it.copy(
                                refinementRunning = true,
                                refinementDone = done,
                                refinementTotal = total,
                                refinementCanResume = false,
                            )
                        }
                        refreshAliasStats(listOf(aliasId))
                        delay(1200)
                        continue
                    }

                    val succeeded = infos.any { it.state == WorkInfo.State.SUCCEEDED }
                    val failed = infos.any { it.state == WorkInfo.State.FAILED || it.state == WorkInfo.State.CANCELLED }
                    when {
                        succeeded -> {
                            val latest = infos.lastOrNull { it.state == WorkInfo.State.SUCCEEDED }
                            val completedFromOutput =
                                latest?.outputData?.getBoolean(AliasFullRefinementWorker.KEY_COMPLETED, true) ?: true
                            // Trust the DB state when it disagrees with the output (e.g. worker was
                            // stopped before reporting completion but more work remains).
                            val dbCompleted =
                                dbState != null && dbState.totalCount > 0 &&
                                    dbState.processedCount >= dbState.totalCount && !dbState.running
                            val completed = completedFromOutput && (dbState == null || dbCompleted || dbState.totalCount == 0)
                            val outputDone = latest?.outputData?.getInt(AliasFullRefinementWorker.KEY_PROGRESS_DONE, -1) ?: -1
                            val outputTotal = latest?.outputData?.getInt(AliasFullRefinementWorker.KEY_PROGRESS_TOTAL, -1) ?: -1
                            val resolvedDone =
                                when {
                                    dbState != null -> dbState.processedCount
                                    outputDone >= 0 -> outputDone
                                    else -> _state.value.refinementDone
                                }
                            val resolvedTotal =
                                when {
                                    dbState != null && dbState.totalCount > 0 -> dbState.totalCount
                                    outputTotal > 0 -> outputTotal
                                    else -> _state.value.refinementTotal
                                }
                            _state.update {
                                it.copy(
                                    refinementRunning = !completed,
                                    refinementDone = resolvedDone,
                                    refinementTotal = resolvedTotal,
                                    refinementCanResume = !completed,
                                    statusMessage =
                                        if (completed) {
                                            "Alias refinement finished."
                                        } else {
                                            "Alias refinement paused. Tap Resume to continue."
                                        },
                                )
                            }
                            refreshAliasStats(listOf(aliasId))
                            if (completed) {
                                loadAliasPreview(aliasId)
                                break
                            }
                            // Worker exited (e.g. stopped by system) but more work remains.
                            // Auto-restart so the user does not have to.
                            enqueueAliasRefinement(aliasId)
                            delay(1500)
                        }
                        failed -> {
                            val resolvedDone = dbState?.processedCount ?: _state.value.refinementDone
                            val resolvedTotal = dbState?.totalCount?.takeIf { it > 0 } ?: _state.value.refinementTotal
                            _state.update {
                                it.copy(
                                    refinementRunning = false,
                                    refinementDone = resolvedDone,
                                    refinementTotal = resolvedTotal,
                                    refinementCanResume = true,
                                    statusMessage = "Alias refinement paused. Tap Resume to continue.",
                                )
                            }
                            break
                        }
                        else -> delay(1200)
                    }
                }
            } finally {
                if (monitoredAliasId == aliasId) monitoredAliasId = null
            }
        }
    }

    private fun refreshRefinementState(aliasId: Long) {
        viewModelScope.launch {
            val state = withContext(Dispatchers.IO) { personAliasService.getRefinementState(aliasId) }
            if (state != null) {
                _state.update {
                    it.copy(
                        refinementDone = state.processedCount,
                        refinementTotal = state.totalCount,
                        refinementRunning = state.running,
                        refinementCanResume = !state.running && state.totalCount > 0 && state.processedCount < state.totalCount,
                    )
                }
            }
        }
    }

    fun loadAliasPreview(aliasId: Long) {
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) { personAliasService.listPendingPreview(aliasId, 10) }
            _state.update {
                it.copy(
                    aliasPreview =
                        rows.map { r -> AliasPreviewItem(r.mediaId, r.confidence, r.status) },
                )
            }
        }
    }

    fun setAliasFeedback(mediaId: Long, accepted: Boolean) {
        val aliasId = _state.value.selectedAliasIdForManage ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { personAliasService.markFeedback(aliasId, mediaId, accepted) }
            loadAliasPreview(aliasId)
            refreshAliasStats(listOf(aliasId))
        }
    }

    fun downloadModels() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, statusMessage = "Preparing models…") }
            try {
                modelStore.ensureModels { msg -> _state.update { s -> s.copy(statusMessage = msg) } }
                modelStore.ensureTokenizer { msg -> _state.update { s -> s.copy(statusMessage = msg) } }
                syncEngineIfPossible(true)
                val ready = modelStore.modelsReady()
                _state.update { it.copy(modelsReady = ready) }
            } catch (e: Exception) {
                _state.update { it.copy(statusMessage = "Model download failed: ${e.message}") }
            } finally {
                _state.update { it.copy(busy = false) }
                refreshCounts()
            }
        }
    }

    private fun syncEngineIfPossible(ready: Boolean) {
        if (!ready) {
            engine?.close()
            engine = null
            return
        }
        if (engine != null) return
        synchronized(this) {
            if (engine != null) return
            engine =
                ClipOnnxEngine(
                    visionModelFile = modelStore.visionFile,
                    textModelFile = modelStore.textFile,
                )
        }
    }

    /**
     * Only treat the MediaStore listing as the full gallery when we have broad read access.
     * With Android 14+ partial photo access, the list can be a subset; pruning would delete valid rows.
     */
    private fun canTrustGalleryListingForPrune(): Boolean =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                ContextCompat.checkSelfPermission(app, Manifest.permission.READ_MEDIA_IMAGES) ==
                    PackageManager.PERMISSION_GRANTED
            else ->
                ContextCompat.checkSelfPermission(app, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
        }

    private fun tokenizer(): ClipTokenizer {
        tokenizer?.let { return it }
        synchronized(this) {
            tokenizer?.let { return it }
            val t = ClipTokenizer(app, bpeFileOverride = modelStore.bpeFile)
            tokenizer = t
            return t
        }
    }

    fun scanNewPhotos() {
        viewModelScope.launch {
            if (!modelStore.modelsReady()) {
                _state.update { it.copy(statusMessage = "Download CLIP models first.") }
                return@launch
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                _state.update {
                    it.copy(
                        scanning = false,
                        statusMessage = "Notification permission is optional. Starting background scan.",
                    )
                }
            }
            try {
                val request = OneTimeWorkRequestBuilder<ScanImagesWorker>().build()
                workManager.enqueueUniqueWork(ScanImagesWorker.WORK_NAME, ExistingWorkPolicy.REPLACE, request)
                _state.update {
                    it.copy(
                        scanning = true,
                        busy = false,
                        scanDone = 0,
                        scanTotal = 0,
                        statusMessage = "Background scan started. You can close the app.",
                    )
                }
                monitorBackgroundScan()
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        scanning = false,
                        statusMessage = "Could not start background scan: ${t.message}",
                    )
                }
            }
        }
    }

    private fun monitorBackgroundScan() {
        viewModelScope.launch {
            while (true) {
                val infos =
                    withContext(Dispatchers.IO) {
                        workManager.getWorkInfosForUniqueWork(ScanImagesWorker.WORK_NAME).get()
                    }
                val info = infos.firstOrNull() ?: break
                when (info.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING -> {
                        val done = info.progress.getInt(ScanImagesWorker.KEY_PROGRESS_DONE, 0)
                        val total = info.progress.getInt(ScanImagesWorker.KEY_PROGRESS_TOTAL, 0)
                        _state.update {
                            it.copy(
                                scanning = true,
                                scanDone = done,
                                scanTotal = total,
                                statusMessage =
                                    if (total > 0) {
                                        "Background scan running: $done / $total"
                                    } else {
                                        "Background scan running…"
                                    },
                            )
                        }
                        delay(1500)
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        val indexedNow = info.outputData.getInt(ScanImagesWorker.KEY_INDEXED_NOW, 0)
                        val skippedUnchanged = info.outputData.getInt(ScanImagesWorker.KEY_SKIPPED_UNCHANGED, 0)
                        val decodeFailures = info.outputData.getInt(ScanImagesWorker.KEY_DECODE_FAILURES, 0)
                        val totalIndexed = info.outputData.getInt(ScanImagesWorker.KEY_TOTAL_INDEXED, 0)
                        val removedStale = info.outputData.getInt(ScanImagesWorker.KEY_REMOVED_STALE, 0)
                        val cleanupMsg = if (removedStale > 0) "Removed $removedStale missing from gallery. " else ""
                        _state.update {
                            it.copy(
                                scanning = false,
                                scanDone = totalIndexed,
                                scanTotal = totalIndexed,
                                indexedCount = totalIndexed.toLong(),
                                statusMessage =
                                    "${cleanupMsg}Scan finished. Indexed: $indexedNow, unchanged: $skippedUnchanged, decode failures: $decodeFailures, total indexed: $totalIndexed.",
                            )
                        }
                        break
                    }
                    WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                        _state.update { it.copy(scanning = false, statusMessage = "Background scan failed.", scanDone = 0, scanTotal = 0) }
                        break
                    }
                    WorkInfo.State.BLOCKED -> {
                        delay(1000)
                    }
                }
            }
            refreshCounts()
        }
    }

    private fun resumeScanMonitorIfNeeded() {
        viewModelScope.launch {
            val infos =
                withContext(Dispatchers.IO) {
                    workManager.getWorkInfosForUniqueWork(ScanImagesWorker.WORK_NAME).get()
                }
            val active =
                infos.any {
                    it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED
                }
            if (active) {
                _state.update { it.copy(scanning = true, statusMessage = "Resumed background scan status.") }
                monitorBackgroundScan()
            }
        }
    }

    /**
     * On app start, find any aliases whose refinement state is incomplete or marked
     * running. Re-enqueue the worker if WorkManager has nothing pending for them
     * (which happens when the system process was killed mid-refinement) and start a
     * monitor so the UI shows the current progress immediately.
     */
    private fun resumeAliasMonitorsIfNeeded() {
        viewModelScope.launch {
            val aliases = withContext(Dispatchers.IO) { personAliasService.listAliases() }
            if (aliases.isEmpty()) return@launch
            var firstActiveAliasId: Long? = null
            var activeDbState: com.halbertb.clipfinder.data.db.AliasRefinementStateEntity? = null
            for (a in aliases) {
                val dbState = withContext(Dispatchers.IO) { personAliasService.getRefinementState(a.aliasId) } ?: continue
                val incomplete = dbState.totalCount > 0 && dbState.processedCount < dbState.totalCount
                if (!incomplete && !dbState.running) continue
                val workName = AliasFullRefinementWorker.WORK_NAME_PREFIX + a.aliasId
                val infos = withContext(Dispatchers.IO) { workManager.getWorkInfosForUniqueWork(workName).get() }
                val pending =
                    infos.any {
                        it.state == WorkInfo.State.ENQUEUED ||
                            it.state == WorkInfo.State.RUNNING ||
                            it.state == WorkInfo.State.BLOCKED
                    }
                if (!pending && incomplete) {
                    enqueueAliasRefinement(a.aliasId)
                }
                if (firstActiveAliasId == null) {
                    firstActiveAliasId = a.aliasId
                    activeDbState = dbState
                }
                monitorAliasRefinement(a.aliasId)
            }
            firstActiveAliasId?.let { aliasId ->
                val dbState = activeDbState
                _state.update {
                    it.copy(
                        selectedAliasIdForManage = it.selectedAliasIdForManage ?: aliasId,
                        refinementRunning = true,
                        refinementDone = dbState?.processedCount ?: it.refinementDone,
                        refinementTotal = dbState?.totalCount?.takeIf { t -> t > 0 } ?: it.refinementTotal,
                        statusMessage = "Resuming alias refinement in background…",
                    )
                }
                loadAliasPreview(aliasId)
            }
        }
    }

    fun search() {
        viewModelScope.launch {
            if (!modelStore.modelsReady()) {
                _state.update { it.copy(statusMessage = "Download CLIP models first.") }
                return@launch
            }
            if (!modelStore.tokenizerReady()) {
                try {
                    _state.update { it.copy(busy = true, statusMessage = "Preparing tokenizer…") }
                    modelStore.ensureTokenizer { msg -> _state.update { s -> s.copy(statusMessage = msg) } }
                    tokenizer = null
                } catch (e: Exception) {
                    _state.update { it.copy(busy = false, statusMessage = "Search failed: ${e.message}") }
                    return@launch
                } finally {
                    _state.update { it.copy(busy = false) }
                }
            }
            syncEngineIfPossible(true)
            val clip = engine ?: return@launch

            val posText = _state.value.positivePrompt.trim()
            if (posText.isEmpty()) {
                _state.update { it.copy(statusMessage = "Enter a positive prompt.") }
                return@launch
            }

            _state.update { it.copy(busy = true, statusMessage = "Searching…") }
            try {
                _state.update { it.copy(statusMessage = "Translating prompts (if needed)…") }
                val translatedPos =
                    withContext(Dispatchers.IO) {
                        promptTranslator.toEnglish(posText)
                    }
                val posVec =
                    withContext(Dispatchers.Default) {
                        clip.encodeText(tokenizer().tokenizeTo77(translatedPos))
                    }
                val negText = _state.value.negativePrompt.trim()
                val negVec =
                    if (negText.isEmpty()) {
                        null
                    } else {
                        val translatedNeg =
                            withContext(Dispatchers.IO) {
                                promptTranslator.toEnglish(negText)
                            }
                        withContext(Dispatchers.Default) {
                            clip.encodeText(tokenizer().tokenizeTo77(translatedNeg))
                        }
                    }

                val rows = withContext(Dispatchers.IO) { dao.getAll() }
                if (rows.isEmpty()) {
                    _state.update { it.copy(searchResults = emptyList(), statusMessage = "No indexed photos yet.") }
                    return@launch
                }
                val aliasId = _state.value.selectedAliasFilterId
                val confidenceMap: Map<Long, Float> =
                    if (aliasId != null) {
                        withContext(Dispatchers.IO) { personAliasService.matchedMediaConfidences(aliasId) }
                    } else {
                        emptyMap()
                    }
                val rowsFiltered =
                    if (aliasId == null) {
                        rows
                    } else {
                        filterRowsByAllowedMediaIds(rows, confidenceMap.keys)
                    }
                if (rowsFiltered.isEmpty()) {
                    _state.update { it.copy(searchResults = emptyList(), statusMessage = "No indexed photos for selected alias.") }
                    return@launch
                }

                val k = _state.value.k
                val boost = aliasId != null && _state.value.boostByAliasConfidence
                // When boosting we score the full filtered set first so a high face
                // confidence can lift a photo into the top-K it would otherwise miss.
                val scoringK = if (boost) rowsFiltered.size else k
                val scored =
                    withContext(Dispatchers.Default) {
                        scoreIndexedImages(rowsFiltered, posVec, negVec, scoringK)
                    }
                val finalItems =
                    if (!boost) {
                        scored.map { s ->
                            SearchResultItem(
                                mediaId = s.mediaId,
                                score = s.score,
                                clipScore = s.score,
                                aliasConfidence = confidenceMap[s.mediaId],
                            )
                        }
                    } else {
                        scored
                            .map { s ->
                                val conf = confidenceMap[s.mediaId] ?: 0f
                                // Soft multiplier: face confidence biases ranking but
                                // a non-face-perfect photo with strong CLIP match is
                                // not crushed completely.
                                val multiplier = 0.5f + 0.5f * conf
                                SearchResultItem(
                                    mediaId = s.mediaId,
                                    score = s.score * multiplier,
                                    clipScore = s.score,
                                    aliasConfidence = conf,
                                )
                            }
                            .sortedByDescending { it.score }
                            .take(k)
                    }
                _state.update {
                    it.copy(
                        searchResults = finalItems,
                        statusMessage = "Showing top ${finalItems.size} results.",
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(statusMessage = "Search failed: ${e.message}") }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun contentUriFor(mediaId: Long): Uri {
        val collection =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
        return ContentUris.withAppendedId(collection, mediaId)
    }

    override fun onCleared() {
        super.onCleared()
        engine?.close()
        promptTranslator.close()
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(MainViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(application) as T
        }
    }
}
