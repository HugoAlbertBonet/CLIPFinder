package com.halbertb.clipfinder.domain.compression

class NativeTurboVecIndex private constructor(
    private var handle: Long,
) : AutoCloseable {
    val vectorCount: Int
        get() = if (handle == 0L) 0 else vectorCountNative(handle)

    fun search(
        query: FloatArray,
        k: Int,
        allowlistMediaIds: LongArray? = null,
    ): NativeTurboVecSearchResult? {
        if (handle == 0L) return null
        return try {
            NativeInt8Vec.parseResult(searchNative(handle, query, k, allowlistMediaIds))
        } catch (t: Throwable) {
            lastFailure = t.message ?: t::class.java.simpleName
            null
        }
    }

    fun write(path: String): Boolean {
        if (handle == 0L) return false
        return try {
            writeNative(handle, path).also { ok ->
                if (!ok) {
                    lastFailure = lastFailure ?: "native TurboVec write returned false"
                }
            }
        } catch (t: Throwable) {
            lastFailure = t.message ?: t::class.java.simpleName
            false
        }
    }

    fun addVectors(
        vectors: FloatArray,
        mediaIds: LongArray,
        dim: Int,
    ): Boolean {
        if (handle == 0L) return false
        return try {
            addVectorsNative(handle, vectors, mediaIds, dim).also { ok ->
                if (!ok) {
                    lastFailure = lastFailure ?: "native TurboVec add returned false"
                }
            }
        } catch (t: Throwable) {
            lastFailure = t.message ?: t::class.java.simpleName
            false
        }
    }

    fun removeIds(mediaIds: LongArray): Boolean {
        if (handle == 0L) return false
        return try {
            removeIdsNative(handle, mediaIds).also { ok ->
                if (!ok) {
                    lastFailure = lastFailure ?: "native TurboVec remove returned false"
                }
            }
        } catch (t: Throwable) {
            lastFailure = t.message ?: t::class.java.simpleName
            false
        }
    }

    override fun close() {
        val current = handle
        if (current != 0L) {
            closeNative(current)
            handle = 0L
        }
    }

    companion object {
        var lastFailure: String? = null
            private set

        fun create(
            vectors: FloatArray,
            mediaIds: LongArray,
            dim: Int,
            bitWidth: Int,
        ): NativeTurboVecIndex? {
            lastFailure = null
            if (!NativeTurboVec.isAvailable) {
                lastFailure = "clipfinder_turbovec native library is unavailable"
                return null
            }
            if (bitWidth !in 2..4) {
                lastFailure = "native TurboVec supports only 2, 3, and 4 bit widths"
                return null
            }
            return try {
                val handle = createNative(vectors, mediaIds, dim, bitWidth)
                if (handle == 0L) {
                    lastFailure = "native TurboVec index creation returned a null handle"
                    null
                } else {
                    NativeTurboVecIndex(handle)
                }
            } catch (t: Throwable) {
                lastFailure = t.message ?: t::class.java.simpleName
                null
            }
        }

        fun load(path: String): NativeTurboVecIndex? {
            lastFailure = null
            if (!NativeTurboVec.isAvailable) {
                lastFailure = "clipfinder_turbovec native library is unavailable"
                return null
            }
            return try {
                val handle = loadNative(path)
                if (handle == 0L) {
                    lastFailure = "native TurboVec load returned a null handle"
                    null
                } else {
                    NativeTurboVecIndex(handle)
                }
            } catch (t: Throwable) {
                lastFailure = t.message ?: t::class.java.simpleName
                null
            }
        }

        @JvmStatic
        private external fun createNative(
            vectors: FloatArray,
            mediaIds: LongArray,
            dim: Int,
            bitWidth: Int,
        ): Long

        @JvmStatic
        private external fun loadNative(path: String): Long

        @JvmStatic
        private external fun searchNative(
            handle: Long,
            query: FloatArray,
            k: Int,
            allowlistMediaIds: LongArray?,
        ): LongArray

        @JvmStatic
        private external fun writeNative(
            handle: Long,
            path: String,
        ): Boolean

        @JvmStatic
        private external fun addVectorsNative(
            handle: Long,
            vectors: FloatArray,
            mediaIds: LongArray,
            dim: Int,
        ): Boolean

        @JvmStatic
        private external fun removeIdsNative(
            handle: Long,
            mediaIds: LongArray,
        ): Boolean

        @JvmStatic
        private external fun vectorCountNative(handle: Long): Int

        @JvmStatic
        private external fun closeNative(handle: Long)
    }
}
