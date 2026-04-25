package com.halbertb.clipfinder.data.media

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GalleryRepository(private val context: Context) {

    suspend fun listAllImages(): List<GalleryMedia> = withContext(Dispatchers.IO) {
        val collection =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

        val projection =
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.MIME_TYPE,
            )

        val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
        val selection = "${MediaStore.Images.Media.SIZE} > 0 AND ${MediaStore.Images.Media.MIME_TYPE} LIKE ?"
        val selectionArgs = arrayOf("image/%")

        val list = ArrayList<GalleryMedia>()
        context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol)
                val modifiedSec = cursor.getLong(modifiedCol)
                val uri = ContentUris.withAppendedId(collection, id)
                list.add(GalleryMedia(id = id, dateModifiedSec = modifiedSec, displayName = name, contentUri = uri))
            }
        }
        list
    }

    fun openBitmapInputStream(uri: android.net.Uri) = context.contentResolver.openInputStream(uri)
}
