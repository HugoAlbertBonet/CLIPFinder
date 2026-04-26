package com.halbertb.clipfinder.data.person

import org.junit.Assert.assertEquals
import org.junit.Test

class PersonAliasRepositoryTest {
    @Test
    fun normalizeAlias_trimsAndLowercases() {
        assertEquals("john doe", PersonAliasRepository.normalizeAlias("  JoHn DoE  "))
    }
}
