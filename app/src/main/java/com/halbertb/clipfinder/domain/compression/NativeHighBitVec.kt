package com.halbertb.clipfinder.domain.compression

class NativeHighBitVec private constructor(
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
                    lastFailure = lastFailure ?: "native high-bit write returned false"
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
            bits: Int,
        ): NativeHighBitVec? {
            lastFailure = null
            if (!NativeTurboVec.isAvailable) {
                lastFailure = "clipfinder_turbovec native library is unavailable"
                return null
            }
            if (bits !in setOf(6, 8, 12)) {
                lastFailure = "faithful native TurboQuant currently supports 6, 8, and 12 bits"
                return null
            }
            return try {
                val handle = createNative(vectors, mediaIds, dim, bits)
                if (handle == 0L) {
                    lastFailure = "native index creation returned a null handle"
                    null
                } else {
                    NativeHighBitVec(handle)
                }
            } catch (t: Throwable) {
                lastFailure = t.message ?: t::class.java.simpleName
                null
            }
        }

        fun load(path: String): NativeHighBitVec? {
            lastFailure = null
            if (!NativeTurboVec.isAvailable) {
                lastFailure = "clipfinder_turbovec native library is unavailable"
                return null
            }
            return try {
                val handle = loadNative(path)
                if (handle == 0L) {
                    lastFailure = "native high-bit load returned a null handle"
                    null
                } else {
                    NativeHighBitVec(handle)
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
            bits: Int,
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
        private external fun vectorCountNative(handle: Long): Int

        @JvmStatic
        private external fun closeNative(handle: Long)
    }
}
