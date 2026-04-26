package com.halbertb.clipfinder.ml.face

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.halbertb.clipfinder.ml.dot
import com.halbertb.clipfinder.ml.l2Normalize
import com.halbertb.clipfinder.util.decodeBitmapForClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.math.max
import kotlin.math.min

data class FaceMatchResult(
    val confidence: Float,
    val faceCount: Int,
    val status: String,
)

class FaceAliasMatcher(
    private val context: Context,
) {
    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(0.1f)
                .build(),
        )
    }

    suspend fun buildReferenceEmbeddings(
        uris: List<Uri>,
        faceEngine: FaceEmbeddingEngine,
    ): List<Pair<FloatArray, Uri>> =
        withContext(Dispatchers.Default) {
            uris.mapNotNull { uri ->
                val faceEmbeddings = extractFaceEmbeddings(uri, faceEngine)
                faceEmbeddings.firstOrNull()?.let { it to uri }
            }
        }

    suspend fun extractFaceEmbeddings(
        imageUri: Uri,
        faceEngine: FaceEmbeddingEngine,
        maxSide: Int = 1200,
        perImageTimeoutMs: Long = PER_IMAGE_TIMEOUT_MS,
    ): List<FloatArray> =
        withContext(Dispatchers.Default) {
            val bitmap =
                withContext(Dispatchers.IO) {
                    runCatching {
                        withTimeout(DECODE_TIMEOUT_MS) {
                            decodeBitmapForClip(context, imageUri, maxSide = maxSide)
                        }
                    }.getOrNull()
                } ?: return@withContext emptyList()
            try {
                withTimeout(perImageTimeoutMs) {
                    detectAndEmbedFaces(bitmap, faceEngine).map { it.first }
                }
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }

    fun scoreAliasMatch(
        faceEmbeddings: List<FloatArray>,
        aliasReferences: List<FloatArray>,
        strongThreshold: Float = 0.62f,
        weakThreshold: Float = 0.52f,
    ): FaceMatchResult {
        if (aliasReferences.isEmpty() || faceEmbeddings.isEmpty()) {
            return FaceMatchResult(0f, faceEmbeddings.size, "rejected")
        }
        val supportThreshold = (weakThreshold - 0.05f).coerceAtLeast(0.30f)
        val minSupport = min(2, aliasReferences.size)
        var best = -1f
        var bestSupport = 0
        for (emb in faceEmbeddings) {
            val sims = aliasReferences.map { ref -> dot(emb, ref) }.sortedDescending()
            val topK = sims.take(min(3, sims.size))
            val avgTopK = if (topK.isEmpty()) -1f else topK.average().toFloat()
            val support = sims.count { it >= supportThreshold }
            if (avgTopK > best || (avgTopK == best && support > bestSupport)) {
                best = avgTopK
                bestSupport = support
            }
        }
        val status =
            when {
                best >= strongThreshold && bestSupport >= minSupport -> "matched"
                best >= weakThreshold -> "uncertain"
                else -> "rejected"
            }
        return FaceMatchResult(confidence = best, faceCount = faceEmbeddings.size, status = status)
    }

    private suspend fun detectAndEmbedFaces(bitmap: Bitmap, faceEngine: FaceEmbeddingEngine): List<Pair<FloatArray, Float>> {
        val input = InputImage.fromBitmap(bitmap, 0)
        val faces = detector.process(input).await()
        if (faces.isEmpty()) return emptyList()
        val result = ArrayList<Pair<FloatArray, Float>>(faces.size)
        for (face in faces) {
            val aligned = FaceAlignment.align(bitmap, face) ?: continue
            try {
                val embedding = l2Normalize(faceEngine.encode(aligned))
                val areaScore = (face.boundingBox.width() * face.boundingBox.height()).toFloat()
                result.add(embedding to areaScore)
            } finally {
                if (!aligned.isRecycled) aligned.recycle()
            }
        }
        return result
    }

    companion object {
        // Hard caps so a single hostile/corrupt image cannot block the worker forever.
        private const val DECODE_TIMEOUT_MS = 15_000L
        private const val PER_IMAGE_TIMEOUT_MS = 25_000L
    }
}
