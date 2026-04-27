package com.halbertb.clipfinder.ml.face

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class FaceEmbeddingModelStore(private val context: Context) {
    private val dir: File
        get() = File(context.filesDir, "models")

    val modelFile: File
        get() = File(dir, MODEL_FILENAME)

    fun modelReady(): Boolean = modelFile.isFile

    suspend fun ensureModel(onStatus: (String) -> Unit) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        if (modelReady()) return@withContext
        if (tryCopyBundledAsset()) {
            if (verifyModelFile(modelFile)) {
                onStatus("Face model installed from app bundle.")
                return@withContext
            } else {
                runCatching { modelFile.delete() }
            }
        }
        onStatus("Downloading face recognition model (~166 MB)…")
        downloadFromAny(MODEL_URLS, modelFile)
        onStatus("Face model ready.")
    }

    private fun tryCopyBundledAsset(): Boolean {
        val assetPath = "models/$MODEL_FILENAME"
        return try {
            context.assets.open(assetPath).use { }
            copyAsset(assetPath, modelFile)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun copyAsset(assetPath: String, out: File) {
        out.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            FileOutputStream(out).use { output -> input.copyTo(output) }
        }
    }

    private fun downloadFile(url: String, out: File) {
        out.parentFile?.mkdirs()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 120_000
        }
        conn.inputStream.use { input ->
            FileOutputStream(out).use { output -> input.copyTo(output) }
        }
        conn.disconnect()
    }

    private fun downloadFromAny(urls: List<String>, out: File) {
        var lastError: Throwable? = null
        for (url in urls) {
            try {
                downloadFile(url, out)
                if (!verifyModelFile(out)) {
                    runCatching { out.delete() }
                    throw IllegalStateException("Downloaded model checksum mismatch: $url")
                }
                return
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw RuntimeException("Could not download face model from known mirrors.", lastError)
    }

    private fun verifyModelFile(file: File): Boolean {
        if (!file.isFile) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { b -> "%02x".format(b) }
        return actual == MODEL_SHA256
    }

    companion object {
        private const val MODEL_FILENAME = "w600k_r50.onnx"
        // Known hash for InsightFace buffalo_l w600k_r50 model artifact.
        private const val MODEL_SHA256 = "4c06341c33c2ca1f86781dab0e829f88ad5b64be9fba56e56bc9ebdefc619e43"
        private val MODEL_URLS =
            listOf(
                "https://huggingface.co/deepghs/insightface/resolve/4e1f33d3fe0e50a0945f3a53ab94ae8977ae7ddb/buffalo_l/w600k_r50.onnx",
            )
    }
}
