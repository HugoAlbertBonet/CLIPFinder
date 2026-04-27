package com.halbertb.clipfinder.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.halbertb.clipfinder.ClipFinderApp
import com.halbertb.clipfinder.data.db.AliasPhotoMembershipEntity
import com.halbertb.clipfinder.ml.dot
import com.halbertb.clipfinder.ml.face.FaceAliasMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FaceDebugAliasStats(
    val processed: Int = 0,
    val detected: Int = 0,
    val matched: Int = 0,
    val uncertain: Int = 0,
    val rejected: Int = 0,
    val avgMatched: Float = 0f,
    val avgRejected: Float = 0f,
    val histogram: List<Int> = List(10) { 0 },
    val borderlineMatched: List<AliasPhotoMembershipEntity> = emptyList(),
    val borderlineRejected: List<AliasPhotoMembershipEntity> = emptyList(),
)

data class ProbeAliasScore(
    val aliasId: Long,
    val alias: String,
    val centroidSimilarity: Float,
    val bestReferenceSimilarity: Float,
    val status: String,
)

data class ProbeFaceResult(
    val index: Int,
    val scores: List<ProbeAliasScore>,
)

data class FaceDebugState(
    val aliases: List<PersonAliasItem> = emptyList(),
    val selectedAliasId: Long? = null,
    val loading: Boolean = false,
    val stats: FaceDebugAliasStats = FaceDebugAliasStats(),
    val probeResults: List<ProbeFaceResult> = emptyList(),
    val message: String = "",
)

class FaceDebugViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ClipFinderApp
    private val aliasService = app.personAliasService
    private val matcher = FaceAliasMatcher(app)

    private val _state = MutableStateFlow(FaceDebugState())
    val state: StateFlow<FaceDebugState> = _state.asStateFlow()

    init {
        refreshAliases()
    }

    fun refreshAliases() {
        viewModelScope.launch {
            val aliases = withContext(Dispatchers.IO) { aliasService.listAliases() }
            val selected = _state.value.selectedAliasId ?: aliases.firstOrNull()?.aliasId
            _state.update {
                it.copy(
                    aliases = aliases.map { a -> PersonAliasItem(aliasId = a.aliasId, alias = a.alias) },
                    selectedAliasId = selected,
                )
            }
            selected?.let { loadStats(it) }
        }
    }

    fun selectAlias(aliasId: Long) {
        _state.update { it.copy(selectedAliasId = aliasId) }
        loadStats(aliasId)
    }

    fun loadStats(aliasId: Long) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val rows = withContext(Dispatchers.IO) { aliasService.listMembership(aliasId) }
            val matched = rows.filter { it.status == "matched" }
            val uncertain = rows.filter { it.status == "uncertain" }
            val rejected = rows.filter { it.status != "matched" && it.status != "uncertain" }
            val histogram = MutableList(10) { 0 }
            rows.forEach { r ->
                val idx = (r.confidence.coerceIn(0f, 0.999f) * 10f).toInt().coerceIn(0, 9)
                histogram[idx] = histogram[idx] + 1
            }
            _state.update {
                it.copy(
                    loading = false,
                    stats =
                        FaceDebugAliasStats(
                            processed = rows.size,
                            detected = rows.count { r -> r.faceCount > 0 },
                            matched = matched.size,
                            uncertain = uncertain.size,
                            rejected = rejected.size,
                            avgMatched = matched.map { m -> m.confidence }.average().toFloatOrZero(),
                            avgRejected = rejected.map { m -> m.confidence }.average().toFloatOrZero(),
                            histogram = histogram,
                            borderlineMatched = matched.sortedBy { r -> r.confidence }.take(8),
                            borderlineRejected = (uncertain + rejected).sortedByDescending { r -> r.confidence }.take(8),
                        ),
                )
            }
        }
    }

    fun runProbe(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, message = "Running probe…") }
            val aliases = withContext(Dispatchers.IO) { aliasService.listAliases() }
            val faceEmbeddings = withContext(Dispatchers.IO) { matcher.extractFaceEmbeddings(uri, app.faceEmbeddingEngine) }
            if (faceEmbeddings.isEmpty()) {
                _state.update { it.copy(loading = false, probeResults = emptyList(), message = "No faces detected in probe image.") }
                return@launch
            }
            val probeRows = ArrayList<ProbeFaceResult>(faceEmbeddings.size)
            for ((idx, emb) in faceEmbeddings.withIndex()) {
                val scores = ArrayList<ProbeAliasScore>(aliases.size)
                for (alias in aliases) {
                    val bundle = withContext(Dispatchers.IO) { aliasService.getAliasReferenceBundle(alias.aliasId) }
                    val threshold = withContext(Dispatchers.IO) { aliasService.getMatchThreshold(alias.aliasId) }
                    val centroid = bundle.centroid?.let { dot(emb, it) } ?: -1f
                    val bestRef = bundle.references.maxOfOrNull { ref -> dot(emb, ref) } ?: -1f
                    val candidate = maxOf(centroid, bestRef)
                    val status =
                        when {
                            candidate >= threshold -> "matched"
                            candidate >= threshold - 0.07f -> "uncertain"
                            else -> "rejected"
                        }
                    scores.add(
                        ProbeAliasScore(
                            aliasId = alias.aliasId,
                            alias = alias.alias,
                            centroidSimilarity = centroid,
                            bestReferenceSimilarity = bestRef,
                            status = status,
                        ),
                    )
                }
                probeRows.add(ProbeFaceResult(index = idx + 1, scores = scores.sortedByDescending { s -> maxOf(s.centroidSimilarity, s.bestReferenceSimilarity) }))
            }
            _state.update { it.copy(loading = false, probeResults = probeRows, message = "Probe complete.") }
        }
    }
}

private fun Double.toFloatOrZero(): Float = if (isNaN()) 0f else toFloat()
