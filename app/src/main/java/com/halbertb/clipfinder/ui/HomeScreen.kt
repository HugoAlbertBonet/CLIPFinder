package com.halbertb.clipfinder.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

@Composable
fun HomeRoute(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    HomeScreen(state = state, viewModel = viewModel)
}

@Composable
private fun HomeScreen(state: MainUiState, viewModel: MainViewModel) {
    var previewUri by remember { mutableStateOf<Uri?>(null) }
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
                        enabled = !state.busy && state.modelsReady,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Scan new photos (background)")
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

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Search", style = MaterialTheme.typography.titleLarge)
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

            if (state.searchResults.isNotEmpty()) {
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
                                    onPreview = { previewUri = it },
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

    if (previewUri != null) {
        ImagePreviewDialog(
            uri = previewUri!!,
            onDismiss = { previewUri = null },
        )
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
