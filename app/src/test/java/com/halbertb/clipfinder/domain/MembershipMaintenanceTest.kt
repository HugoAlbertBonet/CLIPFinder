package com.halbertb.clipfinder.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class MembershipMaintenanceTest {
    @Test
    fun computeDeletedMediaIdsForMembershipCleanup_returnsMissingIds() {
        val stored = listOf(1L, 2L, 3L, 4L)
        val gallery = setOf(2L, 4L)

        val deleted = computeDeletedMediaIdsForMembershipCleanup(stored, gallery)
        assertEquals(listOf(1L, 3L), deleted)
    }
}
