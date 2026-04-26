package com.halbertb.clipfinder.ml.face

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

object FaceAlignment {
    fun align(bitmap: Bitmap, face: Face): Bitmap? {
        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position
        if (leftEye == null || rightEye == null || nose == null) {
            return cropAndResizeFallback(bitmap, face.boundingBox)
        }

        val roi = expandFaceRect(bitmap, face.boundingBox) ?: return null
        val crop = Bitmap.createBitmap(bitmap, roi.left, roi.top, roi.width(), roi.height())
        val out = Bitmap.createBitmap(OUTPUT_SIZE, OUTPUT_SIZE, Bitmap.Config.ARGB_8888)
        try {
            val srcDx = rightEye.x - leftEye.x
            val srcDy = rightEye.y - leftEye.y
            val srcDist = hypot(srcDx.toDouble(), srcDy.toDouble()).toFloat()
            if (srcDist < 1f) return cropAndResizeFallback(bitmap, face.boundingBox)

            val src = floatArrayOf(
                leftEye.x - roi.left, leftEye.y - roi.top,
                rightEye.x - roi.left, rightEye.y - roi.top,
                nose.x - roi.left, nose.y - roi.top,
            )
            val dst = floatArrayOf(
                LEFT_EYE.x, LEFT_EYE.y,
                RIGHT_EYE.x, RIGHT_EYE.y,
                NOSE.x, NOSE.y,
            )
            val matrix = Matrix()
            val ok = matrix.setPolyToPoly(src, 0, dst, 0, 3)
            if (!ok) return cropAndResizeFallback(bitmap, face.boundingBox)
            val canvas = Canvas(out)
            canvas.drawBitmap(crop, matrix, PAINT)
            return out
        } finally {
            if (!crop.isRecycled) crop.recycle()
        }
    }

    private fun cropAndResizeFallback(bitmap: Bitmap, box: Rect): Bitmap? {
        val roi = expandFaceRect(bitmap, box) ?: return null
        val crop = Bitmap.createBitmap(bitmap, roi.left, roi.top, roi.width(), roi.height())
        try {
            return Bitmap.createScaledBitmap(crop, OUTPUT_SIZE, OUTPUT_SIZE, true)
        } finally {
            if (!crop.isRecycled) crop.recycle()
        }
    }

    private fun expandFaceRect(bitmap: Bitmap, box: Rect): Rect? {
        val marginX = (box.width() * 0.30f).toInt()
        val marginY = (box.height() * 0.45f).toInt()
        val left = (box.left - marginX).coerceAtLeast(0)
        val top = (box.top - marginY).coerceAtLeast(0)
        val right = (box.right + marginX).coerceAtMost(bitmap.width)
        val bottom = (box.bottom + marginY).coerceAtMost(bitmap.height)
        val w = min(bitmap.width - left, right - left)
        val h = min(bitmap.height - top, bottom - top)
        if (w <= 8 || h <= 8) return null
        return Rect(left, top, left + w, top + h)
    }

    private val LEFT_EYE = PointF(38.2929f, 51.6963f)
    private val RIGHT_EYE = PointF(73.5318f, 51.5014f)
    private val NOSE = PointF(56.0252f, 71.7366f)
    private const val OUTPUT_SIZE = 112
    private val PAINT = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
}
