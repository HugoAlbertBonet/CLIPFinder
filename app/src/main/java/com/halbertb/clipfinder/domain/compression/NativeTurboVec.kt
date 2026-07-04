package com.halbertb.clipfinder.domain.compression

data class NativeTurboVecItem(
    val mediaId: Long,
    val score: Float,
)

data class NativeTurboVecSearchResult(
    val searchElapsedMs: Long,
    val items: List<NativeTurboVecItem>,
)

object NativeTurboVec {
    val isAvailable: Boolean =
        try {
            System.loadLibrary("clipfinder_turbovec")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }

    fun search(
        vectors: FloatArray,
        mediaIds: LongArray,
        dim: Int,
        bitWidth: Int,
        query: FloatArray,
        k: Int,
    ): NativeTurboVecSearchResult? {
        if (!isAvailable || bitWidth !in 2..4) return null
        return try {
            val raw = searchNative(vectors, mediaIds, dim, bitWidth, query, k)
            if (raw.size < 2) return null
            val elapsedMs = raw[0]
            val count = raw[1].toInt().coerceAtLeast(0)
            val items = ArrayList<NativeTurboVecItem>(count)
            var offset = 2
            repeat(count) {
                if (offset + 1 >= raw.size) return@repeat
                val mediaId = raw[offset]
                val score = Float.fromBits(raw[offset + 1].toInt())
                items += NativeTurboVecItem(mediaId = mediaId, score = score)
                offset += 2
            }
            NativeTurboVecSearchResult(searchElapsedMs = elapsedMs, items = items)
        } catch (_: Throwable) {
            null
        }
    }

    private external fun searchNative(
        vectors: FloatArray,
        mediaIds: LongArray,
        dim: Int,
        bitWidth: Int,
        query: FloatArray,
        k: Int,
    ): LongArray
}
