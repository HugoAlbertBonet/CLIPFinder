package com.halbertb.clipfinder.ml.face

import com.halbertb.clipfinder.ml.dot
import com.halbertb.clipfinder.ml.l2Normalize
import kotlin.math.max

object FaceScoring {
    fun buildCentroid(references: List<FloatArray>): FloatArray? {
        if (references.isEmpty()) return null
        val dim = references.first().size
        if (references.any { it.size != dim }) return null
        val centroid = FloatArray(dim)
        for (ref in references) {
            for (i in 0 until dim) centroid[i] += ref[i]
        }
        for (i in 0 until dim) centroid[i] /= references.size.toFloat()
        return l2Normalize(centroid)
    }

    fun scoreAliasMatch(
        faceEmbeddings: List<FloatArray>,
        aliasReferenceBundle: AliasReferenceBundle,
        strongThreshold: Float = 0.40f,
        weakThreshold: Float = 0.33f,
    ): FaceMatchResult {
        val aliasReferences = aliasReferenceBundle.references
        val centroid = aliasReferenceBundle.centroid
        if ((aliasReferences.isEmpty() && centroid == null) || faceEmbeddings.isEmpty()) {
            return FaceMatchResult(0f, faceEmbeddings.size, "rejected")
        }
        var best = -1f
        for (emb in faceEmbeddings) {
            val bestRef = aliasReferences.maxOfOrNull { ref -> dot(emb, ref) } ?: -1f
            val centroidSim = centroid?.let { dot(emb, it) } ?: -1f
            best = max(best, max(bestRef, centroidSim))
        }
        val status =
            when {
                best >= strongThreshold -> "matched"
                best >= weakThreshold -> "uncertain"
                else -> "rejected"
            }
        return FaceMatchResult(confidence = best, faceCount = faceEmbeddings.size, status = status)
    }
}
