package com.halbertb.clipfinder.ml.face

import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.nio.FloatBuffer

class FaceEmbeddingEngine(
    private val modelStore: FaceEmbeddingModelStore,
) : Closeable {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    @Volatile
    private var sessionHolder: SessionHolder? = null

    fun encode(aligned112: Bitmap): FloatArray {
        require(aligned112.width == INPUT_SIZE && aligned112.height == INPUT_SIZE) {
            "Face embedding expects ${INPUT_SIZE}x${INPUT_SIZE} bitmap."
        }
        val holder = session()
        val chw = FloatArray(3 * INPUT_SIZE * INPUT_SIZE)
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        aligned112.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF).toFloat()
            val g = ((p shr 8) and 0xFF).toFloat()
            val b = (p and 0xFF).toFloat()
            chw[i] = (r - holder.inputMean) / holder.inputStd
            chw[i + INPUT_SIZE * INPUT_SIZE] = (g - holder.inputMean) / holder.inputStd
            chw[i + 2 * INPUT_SIZE * INPUT_SIZE] = (b - holder.inputMean) / holder.inputStd
        }

        val shape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), shape)
        tensor.use { input ->
            holder.session.run(mapOf(holder.inputName to input), setOf(holder.outputName)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val out = extractEmbedding(result[0].value)
                return out.copyOf()
            }
        }
    }

    private fun session(): SessionHolder {
        sessionHolder?.let { return it }
        synchronized(this) {
            sessionHolder?.let { return it }
            check(modelStore.modelReady()) { "Face model is not ready. Call FaceModelStore.ensureReady() first." }
            val session =
                env.createSession(
                    modelStore.modelFile.absolutePath,
                    OrtSession.SessionOptions(),
                )
            val preprocess = detectPreprocessMode(modelStore)
            return SessionHolder(
                session = session,
                outputName = selectOutputName(session),
                inputName = session.inputNames.first(),
                inputMean = preprocess.mean,
                inputStd = preprocess.std,
            ).also { sessionHolder = it }
        }
    }

    private fun selectOutputName(session: OrtSession): String {
        val outputs = session.outputInfo
        val name512 =
            outputs.entries.firstOrNull { (_, info) ->
                val shape = (info.info as? ai.onnxruntime.TensorInfo)?.shape ?: return@firstOrNull false
                shape.lastOrNull() == 512L
            }?.key
        return name512 ?: session.outputNames.first()
    }

    private fun detectPreprocessMode(modelStore: FaceEmbeddingModelStore): PreprocessMode {
        return runCatching {
            val bytes = modelStore.modelFile.readBytes()
            val text = String(bytes, StandardCharsets.ISO_8859_1)
            val hasSub = text.contains("Sub") || text.contains("_minus")
            val hasMul = text.contains("Mul") || text.contains("_mul") || text.contains("Div") || text.contains("_div")
            if (hasSub && hasMul) {
                // Model likely contains input normalization ops in graph.
                PreprocessMode(mean = 0f, std = 1f)
            } else {
                // InsightFace external normalization mode.
                PreprocessMode(mean = 127.5f, std = 127.5f)
            }
        }.getOrDefault(PreprocessMode(mean = 127.5f, std = 127.5f))
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractEmbedding(value: Any?): FloatArray {
        return when (value) {
            is Array<*> -> {
                val first = value.firstOrNull()
                if (first is FloatArray) first else FloatArray(0)
            }
            is FloatArray -> value
            else -> FloatArray(0)
        }.let { out ->
            require(out.size == 512) { "Unexpected face embedding size: ${out.size}" }
            out
        }
    }

    override fun close() {
        sessionHolder?.session?.close()
        sessionHolder = null
    }

    private data class SessionHolder(
        val session: OrtSession,
        val outputName: String,
        val inputName: String,
        val inputMean: Float,
        val inputStd: Float,
    ) {
    }

    private data class PreprocessMode(
        val mean: Float,
        val std: Float,
    )

    companion object {
        const val INPUT_SIZE = 112
    }
}
