package com.halbertb.clipfinder.ml.face

import android.graphics.Matrix
import android.graphics.PointF
import kotlin.math.sqrt

object FaceSimilarityTransform {
    data class SimilarityParams(
        val a: Float,
        val b: Float,
        val tx: Float,
        val ty: Float,
    )

    fun estimate(src: List<PointF>, dst: List<PointF>): Matrix? {
        val params = estimateParams(src, dst) ?: return null
        return Matrix().apply {
            setValues(
                floatArrayOf(
                    params.a, -params.b, params.tx,
                    params.b, params.a, params.ty,
                    0f, 0f, 1f,
                ),
            )
        }
    }

    fun estimateParams(src: List<PointF>, dst: List<PointF>): SimilarityParams? {
        if (src.size != dst.size || src.size < 2) return null
        val n = src.size.toFloat()
        val srcCx = src.sumOf { it.x.toDouble() }.toFloat() / n
        val srcCy = src.sumOf { it.y.toDouble() }.toFloat() / n
        val dstCx = dst.sumOf { it.x.toDouble() }.toFloat() / n
        val dstCy = dst.sumOf { it.y.toDouble() }.toFloat() / n

        var sxx = 0.0
        var sxy = 0.0
        var norm = 0.0
        for (i in src.indices) {
            val sx = src[i].x - srcCx
            val sy = src[i].y - srcCy
            val dx = dst[i].x - dstCx
            val dy = dst[i].y - dstCy
            sxx += sx * dx + sy * dy
            sxy += sx * dy - sy * dx
            norm += sx * sx + sy * sy
        }
        if (norm < 1e-8) return null
        val scale = sqrt((sxx * sxx + sxy * sxy) / (norm * norm)).toFloat()
        if (!scale.isFinite() || scale <= 0f) return null
        val angleCos = (sxx / (scale * norm)).toFloat()
        val angleSin = (sxy / (scale * norm)).toFloat()

        val a = scale * angleCos
        val b = scale * angleSin
        val tx = dstCx - a * srcCx + b * srcCy
        val ty = dstCy - b * srcCx - a * srcCy
        return SimilarityParams(a = a, b = b, tx = tx, ty = ty)
    }
}
