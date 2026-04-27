package com.halbertb.clipfinder.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun FaceDebugScreen(
    vm: FaceDebugViewModel,
    onClose: () -> Unit,
) {
    val state by vm.state.collectAsState()
    var selected by remember { mutableStateOf(state.selectedAliasId?.toString() ?: "") }
    val pickProbe =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
            if (uri != null) vm.runProbe(uri)
        }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Face Debug (hidden)", style = MaterialTheme.typography.titleLarge)
            Text("Diagnostics only. Safe to remove later.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.refreshAliases() }) { Text("Refresh aliases") }
                Button(onClick = onClose) { Text("Close") }
            }
            OutlinedTextField(
                value = selected,
                onValueChange = { selected = it },
                label = { Text("Alias id") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        selected.toLongOrNull()?.let { vm.selectAlias(it) }
                    },
                ) { Text("Load alias stats") }
                Button(onClick = {
                    pickProbe.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("Probe image") }
            }
            Text(state.message, style = MaterialTheme.typography.bodySmall)
            Text(
                "Processed ${state.stats.processed} · Detected ${state.stats.detected} · Matched ${state.stats.matched} · Uncertain ${state.stats.uncertain} · Rejected ${state.stats.rejected}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Avg matched ${"%.3f".format(state.stats.avgMatched)} · Avg rejected ${"%.3f".format(state.stats.avgRejected)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Histogram " + state.stats.histogram.mapIndexed { i, v -> "${i * 10}-${i * 10 + 9}%:$v" }.joinToString(" | "),
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Borderline matched", style = MaterialTheme.typography.titleSmall)
            state.stats.borderlineMatched.forEach { row ->
                Text("media ${row.mediaId} · conf ${"%.3f".format(row.confidence)} · faces ${row.faceCount}", style = MaterialTheme.typography.bodySmall)
            }
            Text("Borderline non-matched", style = MaterialTheme.typography.titleSmall)
            state.stats.borderlineRejected.forEach { row ->
                Text("media ${row.mediaId} · conf ${"%.3f".format(row.confidence)} · ${row.status}", style = MaterialTheme.typography.bodySmall)
            }
            if (state.probeResults.isNotEmpty()) {
                Text("Probe results", style = MaterialTheme.typography.titleMedium)
                state.probeResults.forEach { face ->
                    Text("Face ${face.index}", style = MaterialTheme.typography.titleSmall)
                    face.scores.take(8).forEach { s ->
                        Text(
                            "${s.alias}: centroid=${"%.3f".format(s.centroidSimilarity)} bestRef=${"%.3f".format(s.bestReferenceSimilarity)} => ${s.status}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
