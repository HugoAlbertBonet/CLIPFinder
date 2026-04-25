package com.halbertb.clipfinder.ml

import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val DIM = 512

fun floatArrayToLittleEndianBytes(vec: FloatArray): ByteArray {
    require(vec.size == DIM) { "Expected $DIM floats" }
    val bb = ByteBuffer.allocate(DIM * 4).order(ByteOrder.LITTLE_ENDIAN)
    for (f in vec) bb.putFloat(f)
    return bb.array()
}

fun littleEndianBytesToFloatArray(bytes: ByteArray): FloatArray {
    require(bytes.size == DIM * 4) { "Expected ${DIM * 4} bytes" }
    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val out = FloatArray(DIM)
    for (i in 0 until DIM) out[i] = bb.getFloat()
    return out
}

fun l2Normalize(vec: FloatArray): FloatArray {
    var sum = 0.0
    for (v in vec) sum += (v * v)
    val norm = kotlin.math.sqrt(sum).toFloat().coerceAtLeast(1e-12f)
    return FloatArray(vec.size) { i -> vec[i] / norm }
}

fun dot(a: FloatArray, b: FloatArray): Float {
    var s = 0f
    for (i in a.indices) s += a[i] * b[i]
    return s
}
