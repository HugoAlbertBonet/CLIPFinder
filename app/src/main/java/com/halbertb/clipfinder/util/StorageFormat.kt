package com.halbertb.clipfinder.util

fun formatStorageBytes(bytes: Long): String {
    if (bytes < 1_024L) return "$bytes B"
    if (bytes < 1_024L * 1_024L) return "%.1f KB".format(bytes / 1_024.0)
    if (bytes < 1_024L * 1_024L * 1_024L) return "%.1f MB".format(bytes / (1_024.0 * 1_024.0))
    return "%.2f GB".format(bytes / (1_024.0 * 1_024.0 * 1_024.0))
}
