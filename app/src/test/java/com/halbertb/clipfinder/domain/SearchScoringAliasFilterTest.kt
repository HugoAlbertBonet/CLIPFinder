package com.halbertb.clipfinder.domain

import com.halbertb.clipfinder.data.db.ImageEmbeddingEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchScoringAliasFilterTest {
    @Test
    fun filterRowsByAllowedMediaIds_returnsOnlyAllowedRows() {
        val rows =
            listOf(
                ImageEmbeddingEntity(1L, 1L, ByteArray(512 * 4), 1L),
                ImageEmbeddingEntity(2L, 1L, ByteArray(512 * 4), 1L),
                ImageEmbeddingEntity(3L, 1L, ByteArray(512 * 4), 1L),
            )

        val filtered = filterRowsByAllowedMediaIds(rows, setOf(2L, 3L))
        assertEquals(listOf(2L, 3L), filtered.map { it.mediaId })
    }
}
