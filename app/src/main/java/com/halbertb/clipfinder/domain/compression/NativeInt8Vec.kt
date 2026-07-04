package com.halbertb.clipfinder.domain.compression

class NativeInt8Vec private constructor(
    private var handle: Long,
) : AutoCloseable {
    fun search(
        query: FloatArray,
        k: Int,
    ): NativeTurboVecSearchResult? {
        if (handle == 0L) return null
        return try {
            val raw = searchNative(handle, query, k)
            parseResult(raw)
        } catch (_: Throwable) {
            null
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
        val isAvailable: Boolean =
            try {
                System.loadLibrary("clipfinder_turbovec")
                true
            } catch (_: UnsatisfiedLinkError) {
                false
            }

        fun create(
            vectors: FloatArray,
            mediaIds: LongArray,
            dim: Int,
        ): NativeInt8Vec? {
            if (!isAvailable) return null
            return try {
                val handle = createNative(vectors, mediaIds, dim)
                if (handle == 0L) null else NativeInt8Vec(handle)
            } catch (_: Throwable) {
                null
            }
        }

        fun parseResult(raw: LongArray): NativeTurboVecSearchResult? {
            if (raw.size < 2) return null
            val elapsedMs = raw[0]
            val count = raw[1].toInt().coerceAtLeast(0)
            val items = ArrayList<NativeTurboVecItem>(count)
            var offset = 2
            repeat(count) {
                if (offset + 1 >= raw.size) return@repeat
                items +=
                    NativeTurboVecItem(
                        mediaId = raw[offset],
                        score = Float.fromBits(raw[offset + 1].toInt()),
                    )
                offset += 2
            }
            return NativeTurboVecSearchResult(searchElapsedMs = elapsedMs, items = items)
        }

        @JvmStatic
        private external fun createNative(
            vectors: FloatArray,
            mediaIds: LongArray,
            dim: Int,
        ): Long

        @JvmStatic
        private external fun searchNative(
            handle: Long,
            query: FloatArray,
            k: Int,
        ): LongArray

        @JvmStatic
        private external fun closeNative(handle: Long)
    }
}
