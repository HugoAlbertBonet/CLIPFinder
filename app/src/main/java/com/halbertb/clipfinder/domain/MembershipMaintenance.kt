package com.halbertb.clipfinder.domain

fun computeDeletedMediaIdsForMembershipCleanup(
    storedMediaIds: List<Long>,
    currentGalleryMediaIds: Set<Long>,
): List<Long> = storedMediaIds.filter { it !in currentGalleryMediaIds }
