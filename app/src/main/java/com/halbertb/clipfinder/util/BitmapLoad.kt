package com.halbertb.clipfinder.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import kotlin.math.max

fun decodeBitmapForClip(context: Context, uri: Uri, maxSide: Int = 1024): Bitmap? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val size = info.size
                val largestSide = max(size.width, size.height).coerceAtLeast(1)
                val sample = (largestSide / maxSide).coerceAtLeast(1)
                decoder.setTargetSampleSize(sample)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (_: Exception) {
            // Fallback below for providers/codecs that fail ImageDecoder.
        }
    }

    return tryDecodeWithBitmapFactory(context, uri, maxSide)
}

private fun tryDecodeWithBitmapFactory(context: Context, uri: Uri, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        ?: return null
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
}
