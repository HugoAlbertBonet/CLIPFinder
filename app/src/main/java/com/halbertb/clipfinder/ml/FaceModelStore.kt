package com.halbertb.clipfinder.ml

import android.content.Context
import com.halbertb.clipfinder.ml.face.FaceEmbeddingModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FaceModelStore(private val context: Context) {
    private val modelStore = FaceEmbeddingModelStore(context)

    suspend fun ensureReady(onStatus: (String) -> Unit) =
        withContext(Dispatchers.IO) {
            modelStore.ensureModel(onStatus)
        }

    fun modelsReady(): Boolean = modelStore.modelReady()
}
