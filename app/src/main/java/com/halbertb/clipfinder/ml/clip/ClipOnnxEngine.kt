package com.halbertb.clipfinder.ml.clip

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import com.halbertb.clipfinder.ml.l2Normalize
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class ClipOnnxEngine(
    private val visionModelFile: File,
    private val textModelFile: File,
) {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val sessionOptions = OrtSession.SessionOptions()

    @Volatile
    private var visionSession: OrtSession? = null

    @Volatile
    private var textSession: OrtSession? = null

    private fun vision(): OrtSession {
        visionSession?.let { return it }
        synchronized(this) {
            visionSession?.let { return it }
            val s = env.createSession(visionModelFile.absolutePath, sessionOptions)
            visionSession = s
            return s
        }
    }

    private fun text(): OrtSession {
        textSession?.let { return it }
        synchronized(this) {
            textSession?.let { return it }
            val s = env.createSession(textModelFile.absolutePath, sessionOptions)
            textSession = s
            return s
        }
    }

    fun close() {
        synchronized(this) {
            visionSession?.close()
            textSession?.close()
            visionSession = null
            textSession = null
        }
    }

    fun encodeImage(bitmap: Bitmap): FloatArray {
        val cropped = toClipInput224(bitmap)
        return try {
            val chw = bitmapToNchwFloats(cropped)
            val shape = longArrayOf(1, 3, 224, 224)
            val input = OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), shape)
            vision().run(mapOf("pixel_values" to input)).use { results ->
                input.close()
                val tensor = results[0] as OnnxTensor
                val out = FloatArray(512)
                tensor.floatBuffer.get(out)
                tensor.close()
                l2Normalize(out)
            }
        } finally {
            if (!cropped.isRecycled) cropped.recycle()
        }
    }

    fun encodeText(tokenIds77: LongArray): FloatArray {
        require(tokenIds77.size == 77)
        val shape = longArrayOf(1, 77)
        val input = OnnxTensor.createTensor(env, LongBuffer.wrap(tokenIds77), shape)
        return text().run(mapOf("input_ids" to input)).use { results ->
            input.close()
            val tensor = results[0] as OnnxTensor
            val out = FloatArray(512)
            tensor.floatBuffer.get(out)
            tensor.close()
            l2Normalize(out)
        }
    }

    private fun toClipInput224(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val short = min(w, h)
        val scale = 224f / short
        val nw = max(1, (w * scale).roundToInt())
        val nh = max(1, (h * scale).roundToInt())
        val scaled = Bitmap.createScaledBitmap(src, nw, nh, true)
        val x0 = ((nw - 224) / 2f).roundToInt().coerceIn(0, max(0, nw - 224))
        val y0 = ((nh - 224) / 2f).roundToInt().coerceIn(0, max(0, nh - 224))
        val cropped = Bitmap.createBitmap(scaled, x0, y0, 224, 224)
        if (scaled != src && !scaled.isRecycled) scaled.recycle()
        return cropped
    }

    private fun bitmapToNchwFloats(cropped: Bitmap): FloatArray {
        val h = 224
        val w = 224
        val out = FloatArray(3 * h * w)
        val mean = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        val std = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val px = cropped.getPixel(x, y)
                val rf = ((px shr 16) and 0xFF) / 255f
                val gf = ((px shr 8) and 0xFF) / 255f
                val bf = (px and 0xFF) / 255f
                out[0 * h * w + y * w + x] = (rf - mean[0]) / std[0]
                out[1 * h * w + y * w + x] = (gf - mean[1]) / std[1]
                out[2 * h * w + y * w + x] = (bf - mean[2]) / std[2]
            }
        }
        return out
    }
}
