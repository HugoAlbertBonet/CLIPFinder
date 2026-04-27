package com.halbertb.clipfinder.ml.face

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.halbertb.clipfinder.ml.l2Normalize
import com.halbertb.clipfinder.util.decodeBitmapForClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class AliasReferenceBundle(
    val centroid: FloatArray?,
    val references: List<FloatArray>,
)

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
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(0.05f)
                .build(),
        )
    }

    suspend fun buildReferenceEmbeddings(
        uris: List<Uri>,
        faceEngine: FaceEmbeddingEngine,
    ): List<Pair<FloatArray, Uri>> =
        withContext(Dispatchers.Default) {
            uris.mapNotNull { uri ->
                val faceEmbeddings = extractFaceEmbeddingsWithArea(uri, faceEngine)
                faceEmbeddings.maxByOrNull { it.second }?.first?.let { it to uri }
            }
        }

    fun buildCentroid(references: List<FloatArray>): FloatArray? {
        return FaceScoring.buildCentroid(references)
    }

    suspend fun extractFaceEmbeddings(
        imageUri: Uri,
        faceEngine: FaceEmbeddingEngine,
        maxSide: Int = 1600,
        perImageTimeoutMs: Long = PER_IMAGE_TIMEOUT_MS,
    ): List<FloatArray> =
        extractFaceEmbeddingsWithArea(
            imageUri = imageUri,
            faceEngine = faceEngine,
            maxSide = maxSide,
            perImageTimeoutMs = perImageTimeoutMs,
        ).map { it.first }

    suspend fun extractFaceEmbeddingsWithArea(
        imageUri: Uri,
        faceEngine: FaceEmbeddingEngine,
        maxSide: Int = 1600,
        perImageTimeoutMs: Long = PER_IMAGE_TIMEOUT_MS,
    ): List<Pair<FloatArray, Float>> =
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
                    detectAndEmbedFaces(bitmap, faceEngine)
                }
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }

    fun scoreAliasMatch(
        faceEmbeddings: List<FloatArray>,
        aliasReferenceBundle: AliasReferenceBundle,
        strongThreshold: Float = 0.40f,
        weakThreshold: Float = 0.33f,
    ): FaceMatchResult =
        FaceScoring.scoreAliasMatch(
            faceEmbeddings = faceEmbeddings,
            aliasReferenceBundle = aliasReferenceBundle,
            strongThreshold = strongThreshold,
            weakThreshold = weakThreshold,
        )

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
