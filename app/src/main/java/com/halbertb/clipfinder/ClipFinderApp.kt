package com.halbertb.clipfinder

import android.app.Application
import com.halbertb.clipfinder.data.db.ClipDatabase
import com.halbertb.clipfinder.data.media.GalleryRepository
import com.halbertb.clipfinder.data.person.PersonAliasRepository
import com.halbertb.clipfinder.domain.PersonAliasService
import com.halbertb.clipfinder.ml.ClipModelStore
import com.halbertb.clipfinder.ml.FaceModelStore
import com.halbertb.clipfinder.ml.PromptTranslator
import com.halbertb.clipfinder.ml.face.FaceEmbeddingEngine
import com.halbertb.clipfinder.ml.face.FaceEmbeddingModelStore

class ClipFinderApp : Application() {
    lateinit var database: ClipDatabase
    lateinit var galleryRepository: GalleryRepository
    lateinit var modelStore: ClipModelStore
    lateinit var faceModelStore: FaceModelStore
    lateinit var faceEmbeddingModelStore: FaceEmbeddingModelStore
    lateinit var faceEmbeddingEngine: FaceEmbeddingEngine
    lateinit var promptTranslator: PromptTranslator
    lateinit var personAliasRepository: PersonAliasRepository
    lateinit var personAliasService: PersonAliasService

    override fun onCreate() {
        super.onCreate()
        database = ClipDatabase.build(this)
        galleryRepository = GalleryRepository(this)
        modelStore = ClipModelStore(this)
        faceModelStore = FaceModelStore(this)
        faceEmbeddingModelStore = FaceEmbeddingModelStore(this)
        faceEmbeddingEngine = FaceEmbeddingEngine(faceEmbeddingModelStore)
        promptTranslator = PromptTranslator()
        personAliasRepository =
            PersonAliasRepository(
                aliasDao = database.personAliasDao(),
                referenceDao = database.aliasReferenceFaceDao(),
                membershipDao = database.aliasPhotoMembershipDao(),
                refinementStateDao = database.aliasRefinementStateDao(),
                faceEmbeddingCacheDao = database.faceEmbeddingCacheDao(),
            )
        personAliasService =
            PersonAliasService(
                context = this,
                repository = personAliasRepository,
                faceEmbeddingEngine = faceEmbeddingEngine,
            )
    }

    override fun onTerminate() {
        runCatching { faceEmbeddingEngine.close() }
        super.onTerminate()
    }
}
