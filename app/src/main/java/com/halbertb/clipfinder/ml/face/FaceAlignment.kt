package com.halbertb.clipfinder.ml.face

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.min

object FaceAlignment {
    fun align(bitmap: Bitmap, face: Face): Bitmap? {
        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position
        if (leftEye == null || rightEye == null || nose == null) {
            return cropAndResizeFallback(bitmap, face.boundingBox)
        }
        val mouthLeft = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
        val mouthRight = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position
        return if (mouthLeft != null && mouthRight != null) {
            alignFivePoint(bitmap, leftEye, rightEye, nose, mouthLeft, mouthRight)
                ?: alignThreePoint(bitmap, leftEye, rightEye, nose)
                ?: cropAndResizeFallback(bitmap, face.boundingBox)
        } else {
            alignThreePoint(bitmap, leftEye, rightEye, nose) ?: cropAndResizeFallback(bitmap, face.boundingBox)
        }
    }

    private fun alignFivePoint(
        bitmap: Bitmap,
        leftEye: PointF,
        rightEye: PointF,
        nose: PointF,
        mouthLeft: PointF,
        mouthRight: PointF,
    ): Bitmap? {
        val matrix =
            FaceSimilarityTransform.estimate(
                src = listOf(leftEye, rightEye, nose, mouthLeft, mouthRight),
                dst = listOf(LEFT_EYE, RIGHT_EYE, NOSE, MOUTH_L, MOUTH_R),
            ) ?: return null
        val out = Bitmap.createBitmap(OUTPUT_SIZE, OUTPUT_SIZE, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(bitmap, matrix, PAINT)
        return out
    }

    private fun alignThreePoint(
        bitmap: Bitmap,
        leftEye: PointF,
        rightEye: PointF,
        nose: PointF,
    ): Bitmap? {
        val matrix =
            FaceSimilarityTransform.estimate(
                src = listOf(leftEye, rightEye, nose),
                dst = listOf(LEFT_EYE, RIGHT_EYE, NOSE),
            ) ?: return null
        val out = Bitmap.createBitmap(OUTPUT_SIZE, OUTPUT_SIZE, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(bitmap, matrix, PAINT)
        return out
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
    private val MOUTH_L = PointF(41.5493f, 92.3655f)
    private val MOUTH_R = PointF(70.7299f, 92.2041f)
    private const val OUTPUT_SIZE = 112
    private val PAINT = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
}
