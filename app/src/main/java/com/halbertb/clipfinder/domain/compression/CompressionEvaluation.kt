package com.halbertb.clipfinder.domain.compression

import com.halbertb.clipfinder.data.db.ImageEmbeddingEntity
import com.halbertb.clipfinder.ml.dot
import com.halbertb.clipfinder.ml.l2Normalize
import com.halbertb.clipfinder.ml.littleEndianBytesToFloatArray
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt
import java.util.PriorityQueue

enum class CompressionMethod {
    PCA,
    TURBOQUANT,
}

data class CompressionMetrics(
    val recallAt1: Float,
    val recallAt5: Float,
    val recallAt10: Float,
    val recallAtK: Float,
    val topKOverlap: Int,
    val scoreMae: Float,
    val scoreRmse: Float,
    val meanRankShift: Float,
)

data class CompressionVariantResult(
    val label: String,
    val originalBytes: Long,
    val compressedBytes: Long,
    val compressionRatio: Float,
    val varianceExplained: Float?,
    val metrics: CompressionMetrics,
    val searchElapsedMs: Long,
    val elapsedMs: Long,
)

data class CompressionEvaluationReport(
    val method: CompressionMethod,
    val vectorCount: Int,
    val dimension: Int,
    val baselineSearchElapsedMs: Long,
    val totalElapsedMs: Long,
    val variants: List<CompressionVariantResult>,
)

private data class RankedScore(
    val mediaId: Long,
    val score: Float,
)

private data class PackedQuantizedVector(
    val codes: ByteArray,
    val maxAbs: Float,
    val inverseNorm: Float,
    val bits: Int,
    val dimension: Int,
) {
    val byteSize: Long get() = codes.size + 8L // maxAbs + inverseNorm
}

private sealed interface FastQuantizedVector {
    val maxAbs: Float
    val inverseNorm: Float
    val bits: Int
    val dimension: Int
    val byteSize: Long
}

private data class ByteQuantizedVector(
    val codes: ByteArray,
    override val maxAbs: Float,
    override val inverseNorm: Float,
    override val bits: Int,
    override val dimension: Int,
) : FastQuantizedVector {
    override val byteSize: Long get() = codes.size + 8L // maxAbs + inverseNorm
}

private data class ShortQuantizedVector(
    val codes: ShortArray,
    override val maxAbs: Float,
    override val inverseNorm: Float,
    override val bits: Int,
    override val dimension: Int,
) : FastQuantizedVector {
    override val byteSize: Long get() = codes.size * 2L + 8L // maxAbs + inverseNorm
}

private data class FlatVectors(
    val mediaIds: LongArray,
    val values: FloatArray,
    val count: Int,
    val dimension: Int,
)

private data class PcaProjectedVectors(
    val values: FloatArray,
    val count: Int,
    val components: Int,
    val stride: Int,
)

object CompressionEvaluation {
    private const val LAMBDA = 1f
    private val PCA_COMPONENTS = listOf(16, 32, 64, 128, 256, 300, 400)
    private val NATIVE_TURBOVEC_BITS = listOf(2, 3, 4)
    private val FAITHFUL_NATIVE_TURBOQUANT_BITS = listOf(6, 8, 12)
    private val KOTLIN_EXPERIMENTAL_BITS = listOf(16)

    fun evaluate(
        rows: List<ImageEmbeddingEntity>,
        positive: FloatArray,
        negative: FloatArray?,
        k: Int,
        method: CompressionMethod,
        progress: (String) -> Unit = {},
    ): CompressionEvaluationReport {
        require(rows.isNotEmpty()) { "No indexed embeddings available." }
        val start = System.currentTimeMillis()
        progress("Decoding embeddings...")
        val mediaIds = LongArray(rows.size)
        val vectors =
            Array(rows.size) { i ->
                mediaIds[i] = rows[i].mediaId
                littleEndianBytesToFloatArray(rows[i].embedding)
            }
        val dim = vectors.first().size
        val flatVectors = flatten(mediaIds, vectors)
        val baseline = rankFlat(flatVectors, positive, negative)
        val baselineSearchElapsedMs = measureTopKSearch { rankTopKFlat(flatVectors, positive, negative, k) }
        val variants =
            when (method) {
                CompressionMethod.PCA -> evaluatePca(flatVectors, baseline, positive, negative, k, progress)
                CompressionMethod.TURBOQUANT -> evaluateTurboQuant(mediaIds, vectors, baseline, positive, negative, k, progress)
            }
        return CompressionEvaluationReport(
            method = method,
            vectorCount = vectors.size,
            dimension = dim,
            baselineSearchElapsedMs = baselineSearchElapsedMs,
            totalElapsedMs = System.currentTimeMillis() - start,
            variants = variants,
        )
    }

    private fun evaluatePca(
        flatVectors: FlatVectors,
        baseline: List<RankedScore>,
        positive: FloatArray,
        negative: FloatArray?,
        k: Int,
        progress: (String) -> Unit,
    ): List<CompressionVariantResult> {
        require(flatVectors.count >= 2) { "PCA needs at least 2 embeddings." }
        progress("Fitting PCA...")
        val mean = computeMean(flatVectors)
        val centered = buildCentered(flatVectors, mean)
        val model = fitPca(centered, flatVectors.count, flatVectors.dimension, PCA_COMPONENTS.maxOrNull() ?: 16, mean)
        val originalBytes = flatVectors.count.toLong() * flatVectors.dimension * 4L
        val componentCounts = PCA_COMPONENTS.filter { it <= model.componentCount }
        val maxComponents = componentCounts.maxOrNull() ?: return emptyList()
        progress("Projecting PCA index...")
        val fullProjected = projectPcaIndex(centered, flatVectors.count, model, maxComponents)
        val query = combinedQuery(positive, negative)
        val fullProjectedQuery = projectPcaQuery(query, model, maxComponents)
        val scoreBias = dot(mean, query)
        return componentCounts
            .map { components ->
                val variantStart = System.currentTimeMillis()
                progress("Evaluating PCA $components...")
                val projected = fullProjected.withComponents(components)
                val compressedRank = rankPca(flatVectors.mediaIds, projected, fullProjectedQuery, scoreBias)
                val searchElapsedMs =
                    measureTopKSearch {
                        rankTopKPca(flatVectors.mediaIds, projected, fullProjectedQuery, scoreBias, k)
                    }
                val compressedBytes =
                    flatVectors.count.toLong() * components * 4L +
                        (model.dimension.toLong() * 4L) +
                        (components.toLong() * model.dimension * 4L)
                variant(
                    label = "$components components",
                    originalBytes = originalBytes,
                    compressedBytes = compressedBytes,
                    varianceExplained = model.varianceExplained(components),
                    baseline = baseline,
                    compressed = compressedRank,
                    k = k,
                    searchElapsedMs = searchElapsedMs,
                    elapsedMs = System.currentTimeMillis() - variantStart,
                )
            }
    }

    private fun evaluateTurboQuant(
        mediaIds: LongArray,
        vectors: Array<FloatArray>,
        baseline: List<RankedScore>,
        positive: FloatArray,
        negative: FloatArray?,
        k: Int,
        progress: (String) -> Unit,
    ): List<CompressionVariantResult> {
        val originalBytes = vectors.size.toLong() * vectors.first().size * 4L
        val flatVectors = flatten(mediaIds, vectors)
        val combinedQuery = combinedQuery(positive, negative)
        val variants = mutableListOf<CompressionVariantResult>()
        for (bits in NATIVE_TURBOVEC_BITS) {
            val variantStart = System.currentTimeMillis()
            progress("Evaluating native TurboVec $bits-bit...")
            val native = NativeTurboVec.search(
                vectors = flatVectors.values,
                mediaIds = mediaIds,
                dim = flatVectors.dimension,
                bitWidth = bits,
                query = combinedQuery,
                k = k,
            )
            if (native != null) {
                val compressedRank = native.items.map { RankedScore(it.mediaId, it.score) }
                variants +=
                    variant(
                        label = "$bits-bit native TurboVec",
                        originalBytes = originalBytes,
                        compressedBytes = nativeTurboVecBytes(vectors.size, flatVectors.dimension, bits),
                        varianceExplained = null,
                        baseline = baseline,
                        compressed = compressedRank,
                        k = k,
                        searchElapsedMs = native.searchElapsedMs,
                        elapsedMs = System.currentTimeMillis() - variantStart,
                    )
            }
        }
        run {
            val variantStart = System.currentTimeMillis()
            progress("Evaluating native int8 dot...")
            val nativeInt8 = NativeInt8Vec.create(
                vectors = flatVectors.values,
                mediaIds = mediaIds,
                dim = flatVectors.dimension,
            )
            nativeInt8?.use { index ->
                val native = index.search(combinedQuery, k)
                if (native != null) {
                    val compressedRank = native.items.map { RankedScore(it.mediaId, it.score) }
                    variants +=
                        variant(
                            label = "8-bit native int8 dot",
                            originalBytes = originalBytes,
                            compressedBytes = nativeInt8Bytes(vectors.size, flatVectors.dimension),
                            varianceExplained = null,
                            baseline = baseline,
                            compressed = compressedRank,
                            k = k,
                            searchElapsedMs = native.searchElapsedMs,
                            elapsedMs = System.currentTimeMillis() - variantStart,
                        )
                }
            }
        }
        for (bits in FAITHFUL_NATIVE_TURBOQUANT_BITS) {
            val variantStart = System.currentTimeMillis()
            progress("Evaluating faithful native TurboQuant $bits-bit (no Kotlin fallback)...")
            val nativeHighBit = NativeHighBitVec.create(
                vectors = flatVectors.values,
                mediaIds = mediaIds,
                dim = flatVectors.dimension,
                bits = bits,
            )
            if (nativeHighBit == null) {
                val reason = NativeHighBitVec.lastFailure ?: "unknown native failure"
                error("Faithful native TurboQuant $bits-bit failed: $reason")
            }
            nativeHighBit.use { index ->
                val native = index.search(combinedQuery, k)
                if (native == null) {
                    val reason = NativeHighBitVec.lastFailure ?: "unknown native failure"
                    error("Faithful native TurboQuant $bits-bit failed: $reason")
                }
                val compressedRank = native.items.map { RankedScore(it.mediaId, it.score) }
                variants += variant(
                    label = "$bits-bit native faithful TurboQuant v2",
                    originalBytes = originalBytes,
                    compressedBytes = nativeHighBitBytes(vectors.size, flatVectors.dimension, bits),
                    varianceExplained = null,
                    baseline = baseline,
                    compressed = compressedRank,
                    k = k,
                    searchElapsedMs = native.searchElapsedMs,
                    elapsedMs = System.currentTimeMillis() - variantStart,
                )
            }
        }
        for (bits in KOTLIN_EXPERIMENTAL_BITS) {
            val variantStart = System.currentTimeMillis()
            progress("Evaluating experimental fast Kotlin quantization $bits-bit...")
            val fastIndex = Array(vectors.size) { packFastQuantized(vectors[it], bits) }
            val compressedRank = rankFastQuantized(mediaIds, fastIndex, positive, negative)
            val searchElapsedMs = measureTopKSearch { rankTopKFastQuantized(mediaIds, fastIndex, positive, negative, k) }
            val compressedBytes = fastIndex.sumOf { it.byteSize }
            variants += variant(
                label = "$bits-bit experimental Kotlin quantized",
                originalBytes = originalBytes,
                compressedBytes = compressedBytes,
                varianceExplained = null,
                baseline = baseline,
                compressed = compressedRank,
                k = k,
                searchElapsedMs = searchElapsedMs,
                elapsedMs = System.currentTimeMillis() - variantStart,
            )
        }
        return variants
    }

    private fun variant(
        label: String,
        originalBytes: Long,
        compressedBytes: Long,
        varianceExplained: Float?,
        baseline: List<RankedScore>,
        compressed: List<RankedScore>,
        k: Int,
        searchElapsedMs: Long,
        elapsedMs: Long,
    ): CompressionVariantResult =
        CompressionVariantResult(
            label = label,
            originalBytes = originalBytes,
            compressedBytes = compressedBytes,
            compressionRatio = originalBytes.toFloat() / compressedBytes.coerceAtLeast(1L),
            varianceExplained = varianceExplained,
            metrics = compare(baseline, compressed, k),
            searchElapsedMs = searchElapsedMs,
            elapsedMs = elapsedMs,
        )

    private fun rank(
        mediaIds: LongArray,
        vectors: Array<FloatArray>,
        positive: FloatArray,
        negative: FloatArray?,
    ): List<RankedScore> =
        mediaIds.indices
            .map { i ->
                val pos = dot(vectors[i], positive)
                val neg = if (negative == null) 0f else dot(vectors[i], negative)
                RankedScore(mediaIds[i], if (negative == null) pos else pos - LAMBDA * neg)
            }.sortedByDescending { it.score }

    private fun rankFlat(
        vectors: FlatVectors,
        positive: FloatArray,
        negative: FloatArray?,
    ): List<RankedScore> =
        (0 until vectors.count)
            .map { row ->
                val pos = dotFlat(vectors.values, row * vectors.dimension, vectors.dimension, positive)
                val neg = if (negative == null) 0f else dotFlat(vectors.values, row * vectors.dimension, vectors.dimension, negative)
                RankedScore(vectors.mediaIds[row], if (negative == null) pos else pos - LAMBDA * neg)
            }.sortedByDescending { it.score }

    private fun rankTopK(
        mediaIds: LongArray,
        vectors: Array<FloatArray>,
        positive: FloatArray,
        negative: FloatArray?,
        k: Int,
    ): List<RankedScore> {
        val heap = PriorityQueue<RankedScore>(compareBy { it.score })
        val limit = k.coerceAtLeast(1)
        for (i in vectors.indices) {
            val pos = dot(vectors[i], positive)
            val neg = if (negative == null) 0f else dot(vectors[i], negative)
            offerTopK(heap, limit, RankedScore(mediaIds[i], if (negative == null) pos else pos - LAMBDA * neg))
        }
        return heap.toList().sortedByDescending { it.score }
    }

    private fun rankTopKFlat(
        vectors: FlatVectors,
        positive: FloatArray,
        negative: FloatArray?,
        k: Int,
    ): List<RankedScore> {
        val heap = PriorityQueue<RankedScore>(compareBy { it.score })
        val limit = k.coerceAtLeast(1)
        for (row in 0 until vectors.count) {
            val offset = row * vectors.dimension
            val pos = dotFlat(vectors.values, offset, vectors.dimension, positive)
            val neg = if (negative == null) 0f else dotFlat(vectors.values, offset, vectors.dimension, negative)
            offerTopK(heap, limit, RankedScore(vectors.mediaIds[row], if (negative == null) pos else pos - LAMBDA * neg))
        }
        return heap.toList().sortedByDescending { it.score }
    }

    private fun rankPca(
        mediaIds: LongArray,
        projected: PcaProjectedVectors,
        projectedQuery: FloatArray,
        scoreBias: Float,
    ): List<RankedScore> =
        (0 until projected.count)
            .map { row ->
                val score = scoreBias + dotFlat(projected.values, row * projected.stride, projected.components, projectedQuery)
                RankedScore(mediaIds[row], score)
            }.sortedByDescending { it.score }

    private fun rankTopKPca(
        mediaIds: LongArray,
        projected: PcaProjectedVectors,
        projectedQuery: FloatArray,
        scoreBias: Float,
        k: Int,
    ): List<RankedScore> {
        val heap = PriorityQueue<RankedScore>(compareBy { it.score })
        val limit = k.coerceAtLeast(1)
        for (row in 0 until projected.count) {
            val score = scoreBias + dotFlat(projected.values, row * projected.stride, projected.components, projectedQuery)
            offerTopK(heap, limit, RankedScore(mediaIds[row], score))
        }
        return heap.toList().sortedByDescending { it.score }
    }

    private fun rankPackedQuantized(
        mediaIds: LongArray,
        vectors: Array<PackedQuantizedVector>,
        positive: FloatArray,
        negative: FloatArray?,
    ): List<RankedScore> =
        mediaIds.indices
            .map { i ->
                val pos = dotPackedQuantized(vectors[i], positive)
                val neg = if (negative == null) 0f else dotPackedQuantized(vectors[i], negative)
                RankedScore(mediaIds[i], if (negative == null) pos else pos - LAMBDA * neg)
            }.sortedByDescending { it.score }

    private fun rankTopKPackedQuantized(
        mediaIds: LongArray,
        vectors: Array<PackedQuantizedVector>,
        positive: FloatArray,
        negative: FloatArray?,
        k: Int,
    ): List<RankedScore> {
        val heap = PriorityQueue<RankedScore>(compareBy { it.score })
        val limit = k.coerceAtLeast(1)
        for (i in vectors.indices) {
            val pos = dotPackedQuantized(vectors[i], positive)
            val neg = if (negative == null) 0f else dotPackedQuantized(vectors[i], negative)
            offerTopK(heap, limit, RankedScore(mediaIds[i], if (negative == null) pos else pos - LAMBDA * neg))
        }
        return heap.toList().sortedByDescending { it.score }
    }

    private fun rankFastQuantized(
        mediaIds: LongArray,
        vectors: Array<FastQuantizedVector>,
        positive: FloatArray,
        negative: FloatArray?,
    ): List<RankedScore> =
        mediaIds.indices
            .map { i ->
                val pos = dotFastQuantized(vectors[i], positive)
                val neg = if (negative == null) 0f else dotFastQuantized(vectors[i], negative)
                RankedScore(mediaIds[i], if (negative == null) pos else pos - LAMBDA * neg)
            }.sortedByDescending { it.score }

    private fun rankTopKFastQuantized(
        mediaIds: LongArray,
        vectors: Array<FastQuantizedVector>,
        positive: FloatArray,
        negative: FloatArray?,
        k: Int,
    ): List<RankedScore> {
        val heap = PriorityQueue<RankedScore>(compareBy { it.score })
        val limit = k.coerceAtLeast(1)
        for (i in vectors.indices) {
            val pos = dotFastQuantized(vectors[i], positive)
            val neg = if (negative == null) 0f else dotFastQuantized(vectors[i], negative)
            offerTopK(heap, limit, RankedScore(mediaIds[i], if (negative == null) pos else pos - LAMBDA * neg))
        }
        return heap.toList().sortedByDescending { it.score }
    }

    private fun compare(
        baseline: List<RankedScore>,
        compressed: List<RankedScore>,
        k: Int,
    ): CompressionMetrics {
        val queryK = k.coerceAtLeast(1)
        fun recallAt(n: Int): Float {
            val limit = n.coerceAtMost(queryK)
            val expected = baseline.take(limit).map { it.mediaId }.toSet()
            if (expected.isEmpty()) return 1f
            val actual = compressed.take(limit).map { it.mediaId }.toSet()
            return expected.intersect(actual).size.toFloat() / expected.size
        }

        val baseRanks = baseline.withIndex().associate { it.value.mediaId to it.index }
        val compRanks = compressed.withIndex().associate { it.value.mediaId to it.index }
        val baseScores = baseline.associate { it.mediaId to it.score }
        var rankShift = 0f
        var rankCount = 0
        var mae = 0.0
        var mse = 0.0
        var scoreCount = 0
        for (item in compressed) {
            val baseRank = baseRanks[item.mediaId]
            val compRank = compRanks[item.mediaId]
            if (baseRank != null && compRank != null) {
                rankShift += abs(baseRank - compRank).toFloat()
                rankCount++
            }
            val baseScore = baseScores[item.mediaId]
            if (baseScore != null) {
                val diff = (item.score - baseScore).toDouble()
                mae += abs(diff)
                mse += diff * diff
                scoreCount++
            }
        }
        val topKOverlap =
            baseline.take(queryK).map { it.mediaId }.toSet()
                .intersect(compressed.take(queryK).map { it.mediaId }.toSet())
                .size
        return CompressionMetrics(
            recallAt1 = recallAt(1),
            recallAt5 = recallAt(5),
            recallAt10 = recallAt(10),
            recallAtK = recallAt(queryK),
            topKOverlap = topKOverlap,
            scoreMae = if (scoreCount == 0) 0f else (mae / scoreCount).toFloat(),
            scoreRmse = if (scoreCount == 0) 0f else sqrt(mse / scoreCount).toFloat(),
            meanRankShift = if (rankCount == 0) 0f else rankShift / rankCount,
        )
    }

    private data class PcaModel(
        val mean: FloatArray,
        val componentsFlat: FloatArray,
        val componentCount: Int,
        val dimension: Int,
        val eigenvalues: FloatArray,
    ) {
        fun varianceExplained(count: Int): Float {
            val total = eigenvalues.sum().coerceAtLeast(1e-12f)
            return eigenvalues.take(count).sum() / total
        }
    }

    private fun computeMean(flat: FlatVectors): FloatArray {
        val mean = FloatArray(flat.dimension)
        for (row in 0 until flat.count) {
            val base = row * flat.dimension
            for (i in 0 until flat.dimension) {
                mean[i] += flat.values[base + i]
            }
        }
        val invCount = 1f / flat.count
        for (i in mean.indices) mean[i] *= invCount
        return mean
    }

    private fun buildCentered(
        flat: FlatVectors,
        mean: FloatArray,
    ): FloatArray {
        val centered = FloatArray(flat.values.size)
        for (row in 0 until flat.count) {
            val base = row * flat.dimension
            for (i in 0 until flat.dimension) {
                centered[base + i] = flat.values[base + i] - mean[i]
            }
        }
        return centered
    }

    private fun fitPca(
        centered: FloatArray,
        count: Int,
        dim: Int,
        maxComponents: Int,
        mean: FloatArray,
    ): PcaModel {
        val covariance = FloatArray(dim * dim)
        val scale = 1f / (count - 1).coerceAtLeast(1)
        for (row in 0 until count) {
            val base = row * dim
            for (i in 0 until dim) {
                val xi = centered[base + i]
                val rowOffset = i * dim
                for (j in i until dim) {
                    covariance[rowOffset + j] += xi * centered[base + j]
                }
            }
        }
        for (i in 0 until dim) {
            val rowOffset = i * dim
            for (j in i until dim) {
                val value = covariance[rowOffset + j] * scale
                covariance[rowOffset + j] = value
                covariance[j * dim + i] = value
            }
        }

        val targetComponents = maxComponents.coerceAtMost(dim)
        val componentsFlat = FloatArray(targetComponents * dim)
        val eigenvalues = FloatArray(targetComponents)
        var found = 0
        repeat(targetComponents) { index ->
            val component = powerIterationFlat(covariance, dim)
            val eigenvalue = quadraticFormFlat(covariance, dim, component).coerceAtLeast(0f)
            if (eigenvalue <= 1e-8f) return@repeat
            val dest = index * dim
            for (i in 0 until dim) componentsFlat[dest + i] = component[i]
            eigenvalues[index] = eigenvalue
            found = index + 1
            deflateFlat(covariance, dim, component, eigenvalue)
        }
        return PcaModel(
            mean = mean,
            componentsFlat = if (found == targetComponents) componentsFlat else componentsFlat.copyOf(found * dim),
            componentCount = found,
            dimension = dim,
            eigenvalues = if (found == targetComponents) eigenvalues else eigenvalues.copyOf(found),
        )
    }

    private fun powerIterationFlat(
        matrix: FloatArray,
        dim: Int,
    ): FloatArray {
        var v = l2Normalize(FloatArray(dim) { 1f / dim })
        var iterations = 0
        while (iterations < 50) {
            val next = matvecSymmetricFlat(matrix, dim, v)
            val normalized = l2Normalize(next)
            if (iterations >= 8 && abs(dot(v, normalized)) > 0.99999f) {
                v = normalized
                break
            }
            v = normalized
            iterations++
        }
        return v
    }

    private fun matvecSymmetricFlat(
        matrix: FloatArray,
        dim: Int,
        vector: FloatArray,
    ): FloatArray {
        val out = FloatArray(dim)
        for (i in 0 until dim) {
            var sum = 0f
            val rowOffset = i * dim
            for (j in 0 until i) {
                sum += matrix[j * dim + i] * vector[j]
            }
            sum += matrix[rowOffset + i] * vector[i]
            for (j in i + 1 until dim) {
                sum += matrix[rowOffset + j] * vector[j]
            }
            out[i] = sum
        }
        return out
    }

    private fun quadraticFormFlat(
        matrix: FloatArray,
        dim: Int,
        vector: FloatArray,
    ): Float {
        var sum = 0f
        for (i in 0 until dim) {
            var row = 0f
            val rowOffset = i * dim
            for (j in 0 until dim) row += matrix[rowOffset + j] * vector[j]
            sum += vector[i] * row
        }
        return sum
    }

    private fun deflateFlat(
        matrix: FloatArray,
        dim: Int,
        component: FloatArray,
        eigenvalue: Float,
    ) {
        for (i in 0 until dim) {
            val ci = component[i]
            val rowOffset = i * dim
            for (j in 0 until dim) {
                matrix[rowOffset + j] -= eigenvalue * ci * component[j]
            }
        }
    }

    private fun projectPcaIndex(
        centered: FloatArray,
        count: Int,
        model: PcaModel,
        components: Int,
    ): PcaProjectedVectors {
        val componentCount = components.coerceAtMost(model.componentCount)
        val dim = model.dimension
        val values = FloatArray(count * componentCount)
        for (row in 0 until count) {
            val rowBase = row * dim
            val outBase = row * componentCount
            for (c in 0 until componentCount) {
                val compBase = c * dim
                var sum = 0f
                for (i in 0 until dim) {
                    sum += centered[rowBase + i] * model.componentsFlat[compBase + i]
                }
                values[outBase + c] = sum
            }
        }
        return PcaProjectedVectors(
            values = values,
            count = count,
            components = componentCount,
            stride = componentCount,
        )
    }

    private fun PcaProjectedVectors.withComponents(components: Int): PcaProjectedVectors =
        copy(components = components.coerceAtMost(this.components))

    private fun projectPcaQuery(
        query: FloatArray,
        model: PcaModel,
        components: Int,
    ): FloatArray {
        val componentCount = components.coerceAtMost(model.componentCount)
        val dim = model.dimension
        return FloatArray(componentCount) { c ->
            val compBase = c * dim
            var sum = 0f
            for (i in 0 until dim) sum += query[i] * model.componentsFlat[compBase + i]
            sum
        }
    }

    private fun packQuantized(
        vector: FloatArray,
        bits: Int,
    ): PackedQuantizedVector {
        val levels = 1 shl bits
        val maxAbs = vector.maxOf { abs(it) }.coerceAtLeast(1e-6f)
        val step = (2f * maxAbs) / (levels - 1)
        val codes = ByteArray(ceil(vector.size * bits / 8.0).toInt())
        var normSq = 0.0
        for (i in vector.indices) {
            val code = ((vector[i] + maxAbs) / step).roundToInt().coerceIn(0, levels - 1)
            setPackedCode(codes, i, bits, code)
            val reconstructed = -maxAbs + code * step
            normSq += reconstructed * reconstructed
        }
        val inverseNorm = (1.0 / sqrt(normSq.coerceAtLeast(1e-24))).toFloat()
        return PackedQuantizedVector(
            codes = codes,
            maxAbs = maxAbs,
            inverseNorm = inverseNorm,
            bits = bits,
            dimension = vector.size,
        )
    }

    private fun packFastQuantized(
        vector: FloatArray,
        bits: Int,
    ): FastQuantizedVector {
        val levels = 1 shl bits
        val maxAbs = vector.maxOf { abs(it) }.coerceAtLeast(1e-6f)
        val step = (2f * maxAbs) / (levels - 1)
        var normSq = 0.0
        if (bits <= 8) {
            val codes = ByteArray(vector.size)
            for (i in vector.indices) {
                val code = ((vector[i] + maxAbs) / step).roundToInt().coerceIn(0, levels - 1)
                codes[i] = code.toByte()
                val reconstructed = -maxAbs + code * step
                normSq += reconstructed * reconstructed
            }
            return ByteQuantizedVector(
                codes = codes,
                maxAbs = maxAbs,
                inverseNorm = (1.0 / sqrt(normSq.coerceAtLeast(1e-24))).toFloat(),
                bits = bits,
                dimension = vector.size,
            )
        }

        val codes = ShortArray(vector.size)
        for (i in vector.indices) {
            val code = ((vector[i] + maxAbs) / step).roundToInt().coerceIn(0, levels - 1)
            codes[i] = code.toShort()
            val reconstructed = -maxAbs + code * step
            normSq += reconstructed * reconstructed
        }
        return ShortQuantizedVector(
            codes = codes,
            maxAbs = maxAbs,
            inverseNorm = (1.0 / sqrt(normSq.coerceAtLeast(1e-24))).toFloat(),
            bits = bits,
            dimension = vector.size,
        )
    }

    private fun dotPackedQuantized(
        vector: PackedQuantizedVector,
        query: FloatArray,
    ): Float {
        val levels = 1 shl vector.bits
        val step = (2f * vector.maxAbs) / (levels - 1)
        var sum = 0f
        for (i in 0 until vector.dimension) {
            val code = getPackedCode(vector.codes, i, vector.bits)
            val reconstructed = -vector.maxAbs + code * step
            sum += reconstructed * query[i]
        }
        return sum * vector.inverseNorm
    }

    private fun dotFastQuantized(
        vector: FastQuantizedVector,
        query: FloatArray,
    ): Float =
        when (vector) {
            is ByteQuantizedVector -> dotByteQuantized(vector, query)
            is ShortQuantizedVector -> dotShortQuantized(vector, query)
        }

    private fun dotByteQuantized(
        vector: ByteQuantizedVector,
        query: FloatArray,
    ): Float {
        val levels = 1 shl vector.bits
        val step = (2f * vector.maxAbs) / (levels - 1)
        var sum = 0f
        for (i in 0 until vector.dimension) {
            val code = vector.codes[i].toInt() and 0xFF
            sum += (-vector.maxAbs + code * step) * query[i]
        }
        return sum * vector.inverseNorm
    }

    private fun dotShortQuantized(
        vector: ShortQuantizedVector,
        query: FloatArray,
    ): Float {
        val levels = 1 shl vector.bits
        val step = (2f * vector.maxAbs) / (levels - 1)
        var sum = 0f
        for (i in 0 until vector.dimension) {
            val code = vector.codes[i].toInt() and 0xFFFF
            sum += (-vector.maxAbs + code * step) * query[i]
        }
        return sum * vector.inverseNorm
    }

    private fun setPackedCode(
        bytes: ByteArray,
        index: Int,
        bits: Int,
        code: Int,
    ) {
        var value = code
        var bitOffset = index * bits
        var remaining = bits
        while (remaining > 0) {
            val byteIndex = bitOffset / 8
            val bitInByte = bitOffset % 8
            val chunkBits = minOf(remaining, 8 - bitInByte)
            val chunkMask = (1 shl chunkBits) - 1
            val chunk = value and chunkMask
            bytes[byteIndex] = (bytes[byteIndex].toInt() or (chunk shl bitInByte)).toByte()
            value = value ushr chunkBits
            remaining -= chunkBits
            bitOffset += chunkBits
        }
    }

    private fun getPackedCode(
        bytes: ByteArray,
        index: Int,
        bits: Int,
    ): Int {
        var out = 0
        var outShift = 0
        var bitOffset = index * bits
        var remaining = bits
        while (remaining > 0) {
            val byteIndex = bitOffset / 8
            val bitInByte = bitOffset % 8
            val chunkBits = minOf(remaining, 8 - bitInByte)
            val chunkMask = (1 shl chunkBits) - 1
            val chunk = (bytes[byteIndex].toInt() ushr bitInByte) and chunkMask
            out = out or (chunk shl outShift)
            outShift += chunkBits
            remaining -= chunkBits
            bitOffset += chunkBits
        }
        return out
    }

    private fun flatten(
        mediaIds: LongArray,
        vectors: Array<FloatArray>,
    ): FlatVectors {
        val dim = vectors.first().size
        val values = FloatArray(vectors.size * dim)
        for (row in vectors.indices) {
            vectors[row].copyInto(values, destinationOffset = row * dim)
        }
        return FlatVectors(
            mediaIds = mediaIds,
            values = values,
            count = vectors.size,
            dimension = dim,
        )
    }

    private fun dotFlat(
        values: FloatArray,
        offset: Int,
        dimension: Int,
        query: FloatArray,
    ): Float {
        var sum = 0f
        for (i in 0 until dimension) sum += values[offset + i] * query[i]
        return sum
    }

    private fun offerTopK(
        heap: PriorityQueue<RankedScore>,
        limit: Int,
        item: RankedScore,
    ) {
        if (heap.size < limit) {
            heap.add(item)
        } else if (item.score > (heap.peek()?.score ?: Float.NEGATIVE_INFINITY)) {
            heap.poll()
            heap.add(item)
        }
    }

    private fun measureTopKSearch(block: () -> List<RankedScore>): Long {
        val start = System.nanoTime()
        block()
        return elapsedMsSince(start)
    }

    private fun combinedQuery(
        positive: FloatArray,
        negative: FloatArray?,
    ): FloatArray =
        if (negative == null) {
            positive
        } else {
            FloatArray(positive.size) { i -> positive[i] - negative[i] }
        }

    private fun nativeTurboVecBytes(
        vectorCount: Int,
        dimension: Int,
        bits: Int,
    ): Long {
        val packedCodes = vectorCount.toLong() * (dimension / 8L) * bits
        val vectorScales = vectorCount.toLong() * 4L
        val stableIds = vectorCount.toLong() * 8L
        val tqPlusCalibration = dimension.toLong() * 8L
        return packedCodes + vectorScales + stableIds + tqPlusCalibration
    }

    private fun nativeInt8Bytes(
        vectorCount: Int,
        dimension: Int,
    ): Long {
        val codes = vectorCount.toLong() * dimension
        val vectorScales = vectorCount.toLong() * 4L
        val stableIds = vectorCount.toLong() * 8L
        return codes + vectorScales + stableIds
    }

    private fun nativeHighBitBytes(
        vectorCount: Int,
        dimension: Int,
        bits: Int,
    ): Long {
        val codeBytesPerValue = if (bits <= 8) 1L else 2L
        val codes = vectorCount.toLong() * dimension * codeBytesPerValue
        val vectorScales = vectorCount.toLong() * 4L
        val stableIds = vectorCount.toLong() * 8L
        val tqPlusCalibration = dimension.toLong() * 8L
        return codes + vectorScales + stableIds + tqPlusCalibration
    }
}

fun formatEmbeddingBytes(bytes: Long): String =
    when {
        bytes >= 1_048_576L -> "%.2f MB".format(bytes / 1_048_576.0)
        bytes >= 1024L -> "%.2f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

private fun elapsedMsSince(startNanos: Long): Long =
    ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(0L)
