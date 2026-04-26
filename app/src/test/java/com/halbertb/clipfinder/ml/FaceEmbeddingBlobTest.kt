package com.halbertb.clipfinder.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceEmbeddingBlobTest {
    @Test
    fun faceEmbeddings_roundTripPreservesValues() {
        val first = FloatArray(FACE_EMBED_DIM) { i -> i / 100f }
        val second = FloatArray(FACE_EMBED_DIM) { i -> (FACE_EMBED_DIM - i) / 200f }
        val blob = faceEmbeddingsToBlob(listOf(first, second))

        val decoded = blobToFaceEmbeddings(blob)
        assertEquals(2, decoded.size)
        assertTrue(first.contentEquals(decoded[0]))
        assertTrue(second.contentEquals(decoded[1]))
    }
}
