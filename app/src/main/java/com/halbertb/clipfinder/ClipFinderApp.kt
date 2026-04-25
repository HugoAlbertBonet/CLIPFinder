package com.halbertb.clipfinder

import android.app.Application
import com.halbertb.clipfinder.data.db.ClipDatabase
import com.halbertb.clipfinder.data.media.GalleryRepository
import com.halbertb.clipfinder.ml.ClipModelStore
import com.halbertb.clipfinder.ml.PromptTranslator

class ClipFinderApp : Application() {
    lateinit var database: ClipDatabase
    lateinit var galleryRepository: GalleryRepository
    lateinit var modelStore: ClipModelStore
    lateinit var promptTranslator: PromptTranslator

    override fun onCreate() {
        super.onCreate()
        database = ClipDatabase.build(this)
        galleryRepository = GalleryRepository(this)
        modelStore = ClipModelStore(this)
        promptTranslator = PromptTranslator()
    }
}
