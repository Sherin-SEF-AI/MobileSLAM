package com.mappilot.core.common.hardening

/**
 * Storage-pressure response. Recording is stopped only as a last resort when the
 * disk is critically full (continuing would corrupt the active file); perception
 * (which generates additional artifacts) is shed first. Thresholds are in bytes.
 */
enum class StorageAction { NORMAL, WARN, STOP_NEW_PERCEPTION, STOP_RECORDING }

object StoragePolicy {
    const val CRITICAL_FREE_BYTES = 500L * 1024 * 1024   // 500 MB → stop recording
    const val LOW_FREE_BYTES = 2L * 1024 * 1024 * 1024    // 2 GB → shed perception
    const val WARN_FREE_BYTES = 5L * 1024 * 1024 * 1024   // 5 GB → warn

    fun action(freeBytes: Long): StorageAction = when {
        freeBytes <= CRITICAL_FREE_BYTES -> StorageAction.STOP_RECORDING
        freeBytes <= LOW_FREE_BYTES -> StorageAction.STOP_NEW_PERCEPTION
        freeBytes <= WARN_FREE_BYTES -> StorageAction.WARN
        else -> StorageAction.NORMAL
    }

    /** Human-readable size, base-2. */
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024
        var i = 0
        while (value >= 1024 && i < units.size - 1) { value /= 1024; i++ }
        return "%.1f %s".format(value, units[i])
    }
}
