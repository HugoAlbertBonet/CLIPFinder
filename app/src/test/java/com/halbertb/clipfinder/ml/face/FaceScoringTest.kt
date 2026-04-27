package com.halbertb.clipfinder.ml.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceScoringTest {
    @Test
    fun buildCentroid_returnsNormalizedVector() {
        val refs =
            listOf(
                floatArrayOf(1f, 0f, 0f),
                floatArrayOf(0.8f, 0.2f, 0f),
                floatArrayOf(0.9f, 0.1f, 0f),
            )
        val centroid = FaceScoring.buildCentroid(refs)
        assertNotNull(centroid)
        val norm = kotlin.math.sqrt(centroid!!.sumOf { (it * it).toDouble() }).toFloat()
        assertTrue(kotlin.math.abs(1f - norm) < 1e-4f)
    }

    @Test
    fun scoreAliasMatch_usesMatchedAndUncertainThresholds() {
        val centroid = floatArrayOf(1f, 0f, 0f)
        val faceStrong = floatArrayOf(0.98f, 0.02f, 0f)
        val faceWeak = floatArrayOf(0.36f, 0.64f, 0f)
        val strongResult =
            FaceScoring.scoreAliasMatch(
                faceEmbeddings = listOf(faceStrong),
                aliasReferenceBundle = AliasReferenceBundle(centroid = centroid, references = emptyList()),
                strongThreshold = 0.40f,
                weakThreshold = 0.33f,
            )
        val weakResult =
            FaceScoring.scoreAliasMatch(
                faceEmbeddings = listOf(faceWeak),
                aliasReferenceBundle = AliasReferenceBundle(centroid = centroid, references = emptyList()),
                strongThreshold = 0.40f,
                weakThreshold = 0.33f,
            )
        assertEquals("matched", strongResult.status)
        assertEquals("uncertain", weakResult.status)
    }
}
