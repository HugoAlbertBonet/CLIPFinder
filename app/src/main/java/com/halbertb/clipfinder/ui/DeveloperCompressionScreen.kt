package com.halbertb.clipfinder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.halbertb.clipfinder.domain.compression.CompressionEvaluationReport
import com.halbertb.clipfinder.domain.compression.CompressionMethod
import com.halbertb.clipfinder.domain.compression.CompressionVariantResult
import com.halbertb.clipfinder.domain.compression.formatEmbeddingBytes

@Composable
fun DeveloperCompressionScreen(
    state: MainUiState,
    viewModel: MainViewModel,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Developer", style = MaterialTheme.typography.titleLarge)
            Text(
                "Compare compressed embeddings against the current float32 search baseline.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.compressionMethod == CompressionMethod.PCA,
                    onClick = { viewModel.setCompressionMethod(CompressionMethod.PCA) },
                    label = { Text("PCA") },
                )
                FilterChip(
                    selected = state.compressionMethod == CompressionMethod.TURBOQUANT,
                    onClick = { viewModel.setCompressionMethod(CompressionMethod.TURBOQUANT) },
                    label = { Text("TurboQuant") },
                )
            }
            if (state.compressionMethod == CompressionMethod.TURBOQUANT) {
                Text(
                    "Runs native TurboVec for 2, 3, and 4-bit search, faithful native TurboQuant for 6, 8, and 12-bit search, plus a Kotlin experiment for 16-bit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = state.positivePrompt,
                onValueChange = viewModel::setPositive,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Positive prompt") },
                minLines = 2,
            )
            OutlinedTextField(
                value = state.negativePrompt,
                onValueChange = viewModel::setNegative,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Negative prompt (optional)") },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Top k", style = MaterialTheme.typography.bodyMedium)
                Text(state.k.toString(), style = MaterialTheme.typography.titleMedium)
            }
            Slider(
                value = state.k.toFloat(),
                onValueChange = { viewModel.setK(it.toInt()) },
                valueRange = 1f..50f,
                steps = 48,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Sample limit (0 = all)", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (state.compressionSampleLimit == 0) "all" else state.compressionSampleLimit.toString(),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Slider(
                value = state.compressionSampleLimit.toFloat(),
                onValueChange = { viewModel.setCompressionSampleLimit(it.toInt()) },
                valueRange = 0f..5000f,
                steps = 49,
            )
            Button(
                onClick = { viewModel.runCompressionEvaluation() },
                enabled = state.modelsReady && !state.compressionEvalRunning && !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Run evaluation")
            }
            if (state.compressionEvalRunning) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Text(state.compressionEvalProgress, style = MaterialTheme.typography.bodyMedium)
                }
            }
            state.compressionEvalError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            state.compressionReport?.let { CompressionReport(it) }
        }
    }
}

@Composable
private fun CompressionReport(report: CompressionEvaluationReport) {
    Text("Results", style = MaterialTheme.typography.titleMedium)
    Text(
        "${report.vectorCount} vectors, ${report.dimension} dimensions, ${report.totalElapsedMs} ms",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    report.variants.forEach { VariantResult(it) }
    SearchTimePlot(report)
    QualityMetricsPlot(report)
}

@Composable
private fun VariantResult(variant: CompressionVariantResult) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(variant.label, style = MaterialTheme.typography.titleSmall)
            Text(
                "Memory ${formatEmbeddingBytes(variant.originalBytes)} -> ${formatEmbeddingBytes(variant.compressedBytes)} " +
                    "(${formatFloat(variant.compressionRatio, 1)}x smaller)",
                style = MaterialTheme.typography.bodySmall,
            )
            variant.varianceExplained?.let {
                Text("Variance explained: ${formatFloat(it * 100f, 1)}%", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { it.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
            Text(
                "Recall@1 ${percent(variant.metrics.recallAt1)} | " +
                    "Recall@5 ${percent(variant.metrics.recallAt5)} | " +
                    "Recall@10 ${percent(variant.metrics.recallAt10)} | " +
                    "Recall@K ${percent(variant.metrics.recallAtK)}",
                style = MaterialTheme.typography.bodySmall,
            )
            LinearProgressIndicator(progress = { variant.metrics.recallAtK.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text(
                "Top-K overlap ${variant.metrics.topKOverlap}; rank shift ${formatFloat(variant.metrics.meanRankShift, 2)}; " +
                    "MAE ${formatFloat(variant.metrics.scoreMae, 4)}; RMSE ${formatFloat(variant.metrics.scoreRmse, 4)}; " +
                    "search ${variant.searchElapsedMs} ms; eval ${variant.elapsedMs} ms",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SearchTimePlot(report: CompressionEvaluationReport) {
    val rows = listOf("Float32 baseline" to report.baselineSearchElapsedMs) +
        report.variants.map { it.label to it.searchElapsedMs }
    val maxMs = rows.maxOfOrNull { it.second }?.coerceAtLeast(1L) ?: 1L

    Text("Search time by variant", style = MaterialTheme.typography.titleMedium)
    val timingDescription =
        if (report.method == CompressionMethod.PCA) {
            "Measures scoring and top-K selection in PCA component space for each variant."
        } else {
            "Measures only scoring and top-K selection for the current query. Native TurboVec variants use the Rust SIMD index."
        }
    Text(
        timingDescription,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    rows.forEach { (label, ms) ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = MaterialTheme.typography.bodySmall)
                Text("$ms ms", style = MaterialTheme.typography.bodySmall)
            }
            LinearProgressIndicator(
                progress = { (ms.toFloat() / maxMs).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun QualityMetricsPlot(report: CompressionEvaluationReport) {
    val rows = report.variants.map {
        Triple(it.label, it.metrics.meanRankShift, it.metrics.recallAtK)
    }
    val maxRankShift = rows.maxOfOrNull { it.second }?.coerceAtLeast(1f) ?: 1f

    Text("Quality by variant", style = MaterialTheme.typography.titleMedium)
    Text(
        "Rank shift is lower-is-better; Recall@K is higher-is-better.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    rows.forEach { (label, rankShift, recallAtK) ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Rank shift", style = MaterialTheme.typography.bodySmall)
                Text(formatFloat(rankShift, 2), style = MaterialTheme.typography.bodySmall)
            }
            LinearProgressIndicator(
                progress = { (rankShift / maxRankShift).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Recall@K", style = MaterialTheme.typography.bodySmall)
                Text(percent(recallAtK), style = MaterialTheme.typography.bodySmall)
            }
            LinearProgressIndicator(
                progress = { recallAtK.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun percent(value: Float): String = "${formatFloat(value * 100f, 0)}%"

private fun formatFloat(
    value: Float,
    digits: Int,
): String = "%.${digits}f".format(value)
