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
import com.halbertb.clipfinder.domain.scoreIndexedImages
import com.halbertb.clipfinder.ml.clip.ClipOnnxEngine
import com.halbertb.clipfinder.ml.clip.ClipTokenizer
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
    val busy: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ClipFinderApp
    private val dao = app.database.imageEmbeddingDao()
    private val gallery = app.galleryRepository
    private val modelStore = app.modelStore
    private val promptTranslator = app.promptTranslator
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
        }
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

                val top =
                    withContext(Dispatchers.Default) {
                        scoreIndexedImages(rows, posVec, negVec, _state.value.k)
                    }
                _state.update {
                    it.copy(
                        searchResults = top.map { s -> SearchResultItem(mediaId = s.mediaId, score = s.score) },
                        statusMessage = "Showing top ${top.size} results.",
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
