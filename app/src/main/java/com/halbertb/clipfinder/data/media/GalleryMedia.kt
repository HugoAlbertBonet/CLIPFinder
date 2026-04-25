package com.halbertb.clipfinder.data.media

import android.net.Uri

data class GalleryMedia(
    val id: Long,
    val dateModifiedSec: Long,
    val displayName: String?,
    val contentUri: Uri,
)
