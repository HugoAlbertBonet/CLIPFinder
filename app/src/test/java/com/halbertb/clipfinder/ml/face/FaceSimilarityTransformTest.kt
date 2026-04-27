package com.halbertb.clipfinder.ml.face

import android.graphics.PointF
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceSimilarityTransformTest {
    @Test
    fun estimateParams_recoversScaleRotationTranslation() {
        val src = listOf(PointF(0f, 0f), PointF(1f, 0f), PointF(0f, 1f))
        val angle = Math.toRadians(20.0)
        val scale = 2.0
        val tx = 5.0
        val ty = -3.0
        val cos = kotlin.math.cos(angle).toFloat()
        val sin = kotlin.math.sin(angle).toFloat()
        val dst =
            src.map { p ->
                val x = scale.toFloat() * (cos * p.x - sin * p.y) + tx.toFloat()
                val y = scale.toFloat() * (sin * p.x + cos * p.y) + ty.toFloat()
                PointF(x, y)
            }
        val params = FaceSimilarityTransform.estimateParams(src, dst)
        assertNotNull(params)
        val p = params!!
        assertTrue(kotlin.math.abs(p.tx - tx.toFloat()) < 1e-3f)
        assertTrue(kotlin.math.abs(p.ty - ty.toFloat()) < 1e-3f)
        val recoveredScale = kotlin.math.sqrt((p.a * p.a + p.b * p.b).toDouble()).toFloat()
        assertTrue(kotlin.math.abs(recoveredScale - scale.toFloat()) < 1e-3f)
    }
}
