package com.halbertb.clipfinder.ml

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class ClipModelStore(private val context: Context) {
    private val dir: File
        get() = File(context.filesDir, "models")

    val visionFile: File get() = File(dir, "vision_model_quantized.onnx")
    val textFile: File get() = File(dir, "text_model_quantized.onnx")
    val bpeFile: File get() = File(dir, "bpe_simple_vocab_16e6.txt.gz")

    fun modelsReady(): Boolean = visionFile.isFile && textFile.isFile
    fun tokenizerReady(): Boolean = bpeFile.isFile

    suspend fun ensureModels(
        onStatus: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        if (modelsReady()) return@withContext

        if (tryCopyBundledAssets()) {
            onStatus("Models installed from app bundle.")
            return@withContext
        }

        onStatus("Downloading vision model (~85 MB)…")
        downloadFile(
            "https://huggingface.co/Xenova/clip-vit-base-patch32/resolve/main/onnx/vision_model_quantized.onnx",
            visionFile,
        )
        onStatus("Downloading text model (~62 MB)…")
        downloadFile(
            "https://huggingface.co/Xenova/clip-vit-base-patch32/resolve/main/onnx/text_model_quantized.onnx",
            textFile,
        )
        onStatus("Models ready.")
    }

    suspend fun ensureTokenizer(onStatus: (String) -> Unit) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        if (tokenizerReady()) return@withContext
        if (tryCopyBundledBpeAsset()) {
            onStatus("Tokenizer installed from app bundle.")
            return@withContext
        }
        onStatus("Downloading tokenizer vocabulary…")
        downloadFile(
            "https://github.com/openai/CLIP/raw/refs/heads/main/clip/bpe_simple_vocab_16e6.txt.gz",
            bpeFile,
        )
        onStatus("Tokenizer ready.")
    }

    private fun tryCopyBundledAssets(): Boolean {
        val vPath = "models/vision_model_quantized.onnx"
        val tPath = "models/text_model_quantized.onnx"
        return try {
            context.assets.openFd(vPath).use { }
            context.assets.openFd(tPath).use { }
            copyAsset(vPath, visionFile)
            copyAsset(tPath, textFile)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun tryCopyBundledBpeAsset(): Boolean {
        val bpeAssetPath = "clip/bpe_simple_vocab_16e6.txt.gz"
        return try {
            context.assets.open(bpeAssetPath).use { }
            copyAsset(bpeAssetPath, bpeFile)
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
}
