package com.halbertb.clipfinder.domain

enum class SearchCompressionMode(
    val prefValue: String,
    val title: String,
    val summary: String,
    /** Typical search time vs ~50 ms uncompressed baseline. */
    val typicalSearchMs: Int,
    val baselineSearchMs: Int,
    val meanRankShift: Float,
    val recallPercent: Int,
    val memoryReductionFactor: Float,
) {
    FULL(
        prefValue = "full",
        title = "Full precision",
        summary = "Original float embeddings. Best ranking quality, slowest search.",
        typicalSearchMs = 50,
        baselineSearchMs = 50,
        meanRankShift = 0f,
        recallPercent = 100,
        memoryReductionFactor = 1f,
    ),
    TURBOVEC_4BIT(
        prefValue = "turbovec_4",
        title = "4-bit native TurboVec",
        summary = "Smallest memory footprint and fastest search. Slightly lower recall.",
        typicalSearchMs = 1,
        baselineSearchMs = 50,
        meanRankShift = 8.6f,
        recallPercent = 88,
        memoryReductionFactor = 7.6f,
    ),
    TURBOQUANT_8BIT(
        prefValue = "turboquant_8",
        title = "8-bit faithful TurboQuant v2",
        summary = "Balanced option: much faster than full precision with higher recall than 4-bit.",
        typicalSearchMs = 4,
        baselineSearchMs = 50,
        meanRankShift = 2.5f,
        recallPercent = 96,
        memoryReductionFactor = 3.9f,
    ),
    ;

    val speedupLabel: String
        get() =
            when (this) {
                FULL -> "~${baselineSearchMs} ms/search"
                else -> "~${typicalSearchMs} ms/search (${baselineSearchMs / typicalSearchMs.coerceAtLeast(1)}× faster)"
            }

    val memoryLabel: String
        get() =
            when (this) {
                FULL -> "1× (baseline)"
                else -> "${memoryReductionFactor}× smaller index"
            }

    companion object {
        const val PREF_KEY = "search_compression_mode"

        fun fromPref(value: String?): SearchCompressionMode =
            entries.firstOrNull { it.prefValue == value } ?: FULL
    }
}
