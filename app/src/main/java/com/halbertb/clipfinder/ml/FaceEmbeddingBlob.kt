package com.halbertb.clipfinder.ml

import java.nio.ByteBuffer
import java.nio.ByteOrder

const val FACE_EMBED_DIM = 512

fun faceEmbeddingsToBlob(embeddings: List<FloatArray>): ByteArray {
    if (embeddings.isEmpty()) return ByteArray(0)
    require(embeddings.all { it.size == FACE_EMBED_DIM }) { "All face embeddings must have dimension $FACE_EMBED_DIM." }
    val bb = ByteBuffer.allocate(embeddings.size * FACE_EMBED_DIM * 4).order(ByteOrder.LITTLE_ENDIAN)
    for (vec in embeddings) {
        for (value in vec) bb.putFloat(value)
    }
    return bb.array()
}

fun blobToFaceEmbeddings(blob: ByteArray): List<FloatArray> {
    if (blob.isEmpty()) return emptyList()
    val perFaceBytes = FACE_EMBED_DIM * 4
    require(blob.size % perFaceBytes == 0) { "Invalid face embedding blob size." }
    val count = blob.size / perFaceBytes
    val bb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
    return List(count) {
        FloatArray(FACE_EMBED_DIM) { bb.getFloat() }
    }
}
