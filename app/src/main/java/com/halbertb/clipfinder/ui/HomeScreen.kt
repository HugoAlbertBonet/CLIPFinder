package com.halbertb.clipfinder.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.halbertb.clipfinder.domain.SearchCompressionMode
import com.halbertb.clipfinder.util.formatStorageBytes

@Composable
fun HomeRoute(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    val debugViewModel: FaceDebugViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    HomeScreen(state = state, viewModel = viewModel, debugViewModel = debugViewModel)
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun HomeScreen(state: MainUiState, viewModel: MainViewModel, debugViewModel: FaceDebugViewModel) {
    var previewUri by remember { mutableStateOf<Uri?>(null) }
    var previewClipScore by remember { mutableStateOf<Float?>(null) }
    var previewAliasConfidence by remember { mutableStateOf<Float?>(null) }
    var aliasExampleUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var showDebug by remember { mutableStateOf(false) }
    var searchTapCount by rememberSaveable { mutableStateOf(0) }
    var developerUnlocked by rememberSaveable { mutableStateOf(false) }
    val pickAliasPhotosLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 8),
        ) { uris ->
            aliasExampleUris = uris
        }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "CLIP Finder",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "On-device CLIP search for your photo library.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.selectedScreen == "search",
                    onClick = {
                        val nextTapCount = searchTapCount + 1
                        if (nextTapCount >= 5) {
                            developerUnlocked = true
                            searchTapCount = 0
                            viewModel.setScreen("developer")
                        } else {
                            searchTapCount = nextTapCount
                            viewModel.setScreen("search")
                        }
                    },
                    label = { Text("Search") },
                )
                Box(
                    modifier =
                        Modifier.combinedClickable(
                            onClick = {
                                searchTapCount = 0
                                viewModel.setScreen("people")
                            },
                            onLongClick = { showDebug = true },
                        ),
                ) {
                    FilterChip(
                        selected = state.selectedScreen == "people",
                        onClick = {
                            searchTapCount = 0
                            viewModel.setScreen("people")
                        },
                        label = { Text("People") },
                    )
                }
                if (developerUnlocked) {
                    FilterChip(
                        selected = state.selectedScreen == "developer",
                        onClick = {
                            searchTapCount = 0
                            viewModel.setScreen("developer")
                        },
                        label = { Text("Developer") },
                    )
                }
            }

            if (showDebug) {
                FaceDebugScreen(
                    vm = debugViewModel,
                    onClose = { showDebug = false },
                )
            }

            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text("Indexed photos", style = MaterialTheme.typography.labelLarge)
                            Text(
                                text = state.indexedCount.toString(),
                                style = MaterialTheme.typography.titleLarge,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Models", style = MaterialTheme.typography.labelLarge)
                            Text(
                                text = if (state.modelsReady) "Ready" else "Not installed",
                                style = MaterialTheme.typography.titleLarge,
                                color =
                                    if (state.modelsReady) {
                                        MaterialTheme.colorScheme.tertiary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                            )
                        }
                    }

                    if (!state.modelsReady) {
                        Button(
                            onClick = { viewModel.downloadModels() },
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.Download, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("Download CLIP models")
                        }
                    }

                    Button(
                        onClick = { viewModel.scanNewPhotos() },
                        enabled = !state.busy && state.modelsReady && !state.compressing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Scan new photos (background)")
                    }

                    OutlinedButton(
                        onClick = { viewModel.requestCompressDatabase() },
                        enabled =
                            !state.busy &&
                                !state.scanning &&
                                !state.compressing &&
                                state.modelsReady &&
                                !state.floatsRemoved,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.Storage, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Compress database")
                    }

                    if (state.compressing) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            text = state.compressProgress.ifBlank { "Compressing…" },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    if (state.compressedVectorCount > 0) {
                        Text(
                            text =
                                buildString {
                                    append("Compressed index: ${state.compressedVectorCount} vectors")
                                    state.compressedModePref?.let { pref ->
                                        append(" (")
                                        append(SearchCompressionMode.fromPref(pref).title)
                                        append(")")
                                    }
                                    if (state.floatsRemoved) {
                                        append(" — floats removed")
                                    }
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (state.scanning && state.scanTotal > 0) {
                        LinearProgressIndicator(
                            progress = { state.scanDone / state.scanTotal.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = "Processed ${state.scanDone} / ${state.scanTotal}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            if (state.selectedScreen == "search") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("Search", style = MaterialTheme.typography.titleLarge)
                        AliasFilterPicker(
                            aliases = state.aliases,
                            selectedAliasId = state.selectedAliasFilterId,
                            onSelect = { viewModel.setAliasFilterId(it) },
                        )
                        if (state.selectedAliasFilterId != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Checkbox(
                                    checked = state.boostByAliasConfidence,
                                    onCheckedChange = { viewModel.setBoostByAliasConfidence(it) },
                                )
                                Text(
                                    text = "Boost ranking by face match strength",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                        OutlinedTextField(
                            value = state.positivePrompt,
                            onValueChange = viewModel::setPositive,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Positive prompt") },
                            singleLine = false,
                            minLines = 2,
                        )
                        OutlinedTextField(
                            value = state.negativePrompt,
                            onValueChange = viewModel::setNegative,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Negative prompt (optional)") },
                            singleLine = false,
                            minLines = 2,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Top k", style = MaterialTheme.typography.bodyLarge)
                            Text(text = state.k.toString(), style = MaterialTheme.typography.titleLarge)
                        }
                        Slider(
                            value = state.k.toFloat(),
                            onValueChange = { viewModel.setK(it.toInt()) },
                            valueRange = 1f..50f,
                            steps = 48,
                        )
                        if (state.compressedVectorCount > 0 && state.compressedModePref != null) {
                            val activeMode = SearchCompressionMode.fromPref(state.compressedModePref)
                            Text(
                                text =
                                    if (state.floatsRemoved) {
                                        "Search uses ${activeMode.title} (${activeMode.typicalSearchMs} ms typical)."
                                    } else {
                                        "Search uses ${activeMode.title} index; float copies still on disk until you remove them."
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(
                            onClick = { viewModel.search() },
                            enabled = !state.busy && state.modelsReady,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.ImageSearch, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("Search library")
                        }
                    }
                }
            } else if (state.selectedScreen == "developer") {
                DeveloperCompressionScreen(state = state, viewModel = viewModel)
            } else {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("People aliases", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(
                            value = state.aliasInput,
                            onValueChange = viewModel::setAliasInput,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Alias name") },
                            singleLine = true,
                        )
                        Button(
                            onClick = {
                                pickAliasPhotosLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Pick example photos")
                        }
                        Text(
                            text = "Selected examples: ${aliasExampleUris.size}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(
                            onClick = { viewModel.createAliasFromUris(aliasExampleUris) },
                            enabled = !state.busy && state.modelsReady,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Create alias and start refinement")
                        }
                        if (state.aliases.isNotEmpty()) {
                            Text("Saved aliases", style = MaterialTheme.typography.titleMedium)
                            state.aliases.forEach { alias ->
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Button(
                                            onClick = { viewModel.selectAliasForManage(alias.aliasId) },
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Text(alias.alias)
                                        }
                                        Button(
                                            onClick = { viewModel.deleteAlias(alias.aliasId) },
                                        ) {
                                            Text("Delete")
                                        }
                                    }
                                    val stats = state.aliasStats[alias.aliasId]
                                    if (stats != null) {
                                        Text(
                                            text =
                                                "Processed ${stats.processed} • " +
                                                    "Included ${stats.included} • " +
                                                    "Not included ${stats.notIncluded} • " +
                                                    "Failed ${stats.errors}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    } else {
                                        Text(
                                            text = "Stats loading…",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        val managedAliasId = state.selectedAliasIdForManage
                        if (managedAliasId != null) {
                            val threshold = state.aliasThresholds[managedAliasId] ?: 0.55f
                            Text(
                                text = "Match threshold: ${(threshold * 100).toInt()}%",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = "MobileFaceNet cosine similarity above this is tagged as the alias.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Slider(
                                value = threshold,
                                onValueChange = { viewModel.setAliasThreshold(managedAliasId, it) },
                                onValueChangeFinished = { viewModel.commitAliasThreshold(managedAliasId) },
                                valueRange = 0.20f..0.80f,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(
                                onClick = { viewModel.reclassifyAliasFromCache() },
                                enabled = !state.reclassifying && !state.refinementRunning,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Reclassify with this threshold")
                            }
                            if (state.reclassifying && state.reclassifyTotal > 0) {
                                LinearProgressIndicator(
                                    progress = { state.reclassifyDone / state.reclassifyTotal.toFloat() },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text("Reclassifying ${state.reclassifyDone} / ${state.reclassifyTotal}")
                            }
                        }
                        if (state.refinementRunning && state.refinementTotal > 0) {
                            LinearProgressIndicator(
                                progress = { state.refinementDone / state.refinementTotal.toFloat() },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text("Refining ${state.refinementDone} / ${state.refinementTotal}")
                        } else if (state.refinementCanResume && state.selectedAliasIdForManage != null) {
                            Button(
                                onClick = { viewModel.resumeAliasRefinement() },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Resume refinement")
                            }
                            if (state.refinementTotal > 0) {
                                Text("Paused at ${state.refinementDone} / ${state.refinementTotal}")
                            }
                        }
                        if (state.aliasPreview.isNotEmpty()) {
                            Text("Validation preview", style = MaterialTheme.typography.titleMedium)
                            val current = state.aliasPreview.first()
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ResultTile(
                                    modifier = Modifier.weight(1f),
                                    uri = viewModel.contentUriFor(current.mediaId),
                                    score = current.confidence,
                                    onPreview = { uri ->
                                        previewUri = uri
                                        previewClipScore = null
                                        previewAliasConfidence = current.confidence
                                    },
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Current: ${current.status}", style = MaterialTheme.typography.labelLarge)
                                    Button(onClick = { viewModel.setAliasFeedback(current.mediaId, true) }) { Text("Confirm") }
                                    Button(onClick = { viewModel.setAliasFeedback(current.mediaId, false) }) { Text("Refuse") }
                                }
                            }
                            if (state.aliasPreview.size > 1) {
                                Text(
                                    "Up next: ${state.aliasPreview.size - 1}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    state.aliasPreview.drop(1).take(3).forEach { item ->
                                        ResultTile(
                                            modifier = Modifier.weight(1f),
                                            uri = viewModel.contentUriFor(item.mediaId),
                                            score = item.confidence,
                                            onPreview = { uri ->
                                                previewUri = uri
                                                previewClipScore = null
                                                previewAliasConfidence = item.confidence
                                            },
                                        )
                                    }
                                    repeat((3 - state.aliasPreview.drop(1).take(3).size).coerceAtLeast(0)) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (state.statusMessage.isNotBlank()) {
                Text(
                    text = state.statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.busy && !state.scanning) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    Text("Working…", style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (state.selectedScreen == "search" && state.searchResults.isNotEmpty()) {
                Text("Results", style = MaterialTheme.typography.titleLarge)
                val rows = state.searchResults.chunked(3)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    rows.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            row.forEach { item ->
                                ResultTile(
                                    modifier = Modifier.weight(1f),
                                    uri = viewModel.contentUriFor(item.mediaId),
                                    score = item.score,
                                    onPreview = { uri ->
                                        previewUri = uri
                                        previewClipScore = item.clipScore
                                        previewAliasConfidence = item.aliasConfidence
                                    },
                                )
                            }
                            repeat(3 - row.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    if (state.showCompressModeDialog) {
        CompressModeChoiceDialog(
            isAvailable = viewModel::isCompressionModeAvailable,
            onDismiss = { viewModel.dismissCompressModeDialog() },
            onConfirm = { viewModel.compressDatabase(it) },
        )
    }

    if (state.pendingDeleteFloatsDialog) {
        val floatBytes = state.compressStorageFloatBytes
        val indexBytes = state.compressStorageIndexBytes
        val totalKept = floatBytes + indexBytes
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteFloatsDialog() },
            title = { Text("Remove float embeddings?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Compression finished. Here is how much space the search index uses on disk:",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    MetricLine(
                        label = "Float embeddings (SQLite)",
                        value = formatStorageBytes(floatBytes),
                    )
                    MetricLine(
                        label = "Compressed index file",
                        value = formatStorageBytes(indexBytes),
                    )
                    MetricLine(
                        label = "Total if you keep both",
                        value = formatStorageBytes(totalKept),
                    )
                    MetricLine(
                        label = "Total if you remove floats",
                        value = formatStorageBytes(indexBytes),
                    )
                    Text(
                        text = "Removing floats frees ${formatStorageBytes(floatBytes)}. " +
                            "Full-precision search will be unavailable until you scan again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDeleteFloatEmbeddings() }) {
                    Text("Remove floats")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDeleteFloatsDialog() }) {
                    Text("Keep floats")
                }
            },
        )
    }

    if (previewUri != null) {
        ImagePreviewDialog(
            uri = previewUri!!,
            clipScore = previewClipScore,
            aliasConfidence = previewAliasConfidence,
            onDismiss = {
                previewUri = null
                previewClipScore = null
                previewAliasConfidence = null
            },
        )
    }
}

@Composable
private fun CompressModeChoiceDialog(
    isAvailable: (SearchCompressionMode) -> Boolean,
    onDismiss: () -> Unit,
    onConfirm: (SearchCompressionMode) -> Unit,
) {
    var selected by remember { mutableStateOf(SearchCompressionMode.TURBOQUANT_8BIT) }
    val selectableModes =
        listOf(
            SearchCompressionMode.TURBOVEC_4BIT,
            SearchCompressionMode.TURBOQUANT_8BIT,
        )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose compression") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Pick how aggressively to compress your scanned embeddings. Search will use this index after compression.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Rank shift: lower is better. Recall@K: higher is better.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                selectableModes.forEach { mode ->
                    val enabled = isAvailable(mode)
                    val isSelected = selected == mode
                    Card(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .then(
                                    if (enabled) {
                                        Modifier.clickable { selected = mode }
                                    } else {
                                        Modifier
                                    },
                                )
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color =
                                        when {
                                            isSelected -> MaterialTheme.colorScheme.primary
                                            enabled -> MaterialTheme.colorScheme.outlineVariant
                                            else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                                        },
                                    shape = RoundedCornerShape(12.dp),
                                ),
                        colors =
                            CardDefaults.cardColors(
                                containerColor =
                                    if (isSelected) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                    },
                            ),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { if (enabled) selected = mode },
                                    enabled = enabled,
                                )
                                Text(mode.title, style = MaterialTheme.typography.titleSmall)
                            }
                            Text(mode.summary, style = MaterialTheme.typography.bodySmall)
                            MetricLine(label = "Typical search", value = mode.speedupLabel)
                            MetricLine(
                                label = "Mean rank shift vs full precision",
                                value = "%.1f".format(mode.meanRankShift),
                            )
                            MetricLine(label = "Recall@K vs full precision", value = "${mode.recallPercent}%")
                            MetricLine(label = "Index memory", value = mode.memoryLabel)
                            if (!enabled) {
                                Text(
                                    text = "Unavailable on this device (native library missing).",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selected) },
                enabled = isAvailable(selected),
            ) {
                Text("Start compression")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun MetricLine(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AliasFilterPicker(
    aliases: List<PersonAliasItem>,
    selectedAliasId: Long?,
    onSelect: (Long?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = aliases.firstOrNull { it.aliasId == selectedAliasId }?.alias ?: "(none)"
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text("Person alias filter (optional)") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("(none)") },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            aliases.forEach { alias ->
                DropdownMenuItem(
                    text = { Text(alias.alias) },
                    onClick = {
                        onSelect(alias.aliasId)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ResultTile(
    modifier: Modifier = Modifier,
    uri: Uri,
    score: Float,
    onPreview: (Uri) -> Unit,
) {
    Card(
        modifier =
            modifier
                .height(150.dp)
                .clickable { onPreview(uri) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Box {
            AsyncImage(
                model = uri,
                contentDescription = null,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .clip(MaterialTheme.shapes.medium),
                contentScale = ContentScale.Crop,
            )
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "score %.3f".format(score),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ImagePreviewDialog(
    uri: Uri,
    clipScore: Float?,
    aliasConfidence: Float?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AsyncImage(
                    model = uri,
                    contentDescription = null,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(420.dp)
                            .clip(MaterialTheme.shapes.medium),
                    contentScale = ContentScale.Fit,
                )
                if (clipScore != null || aliasConfidence != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (clipScore != null) {
                            Text(
                                text = "CLIP score: %.3f".format(clipScore),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (aliasConfidence != null) {
                            Text(
                                text = "Alias face similarity: %.3f (%.0f%%)".format(
                                    aliasConfidence,
                                    aliasConfidence * 100f,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    ) { Text("Close") }
                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, "image/*") }
                            context.startActivity(Intent.createChooser(intent, "Open image"))
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Open in gallery") }
                }
            }
        }
    }
}
