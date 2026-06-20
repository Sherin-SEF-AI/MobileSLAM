package com.mappilot.app.hardening

import android.content.Context
import android.os.StatFs
import com.mappilot.core.common.dispatcher.DispatcherProvider
import com.mappilot.core.common.hardening.StoragePolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class StorageStatus(
    val freeBytes: Long,
    val totalBytes: Long,
    val tripsBytes: Long,
) {
    val freeHuman get() = StoragePolicy.formatBytes(freeBytes)
    val tripsHuman get() = StoragePolicy.formatBytes(tripsBytes)
}

/**
 * Monitors free storage and accounts trip usage. On pressure it drives
 * [DegradationController]; recording is only requested to stop when the disk is
 * critically full (a full disk would corrupt the active file). Handles
 * 100 GB-scale datasets by summing lazily and never loading file contents.
 */
@Singleton
class StorageManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val degradation: DegradationController,
    private val dispatchers: DispatcherProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)

    private val tripsRoot: File get() = File(context.getExternalFilesDir(null), "trips")

    fun start() {
        scope.coroutineContext.cancelChildren()
        scope.launch {
            while (true) {
                check()
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    fun stop() = scope.coroutineContext.cancelChildren()

    fun status(): StorageStatus {
        val stat = StatFs((context.getExternalFilesDir(null) ?: context.filesDir).absolutePath)
        return StorageStatus(stat.availableBytes, stat.totalBytes, tripsBytes())
    }

    private fun check() {
        val free = status().freeBytes
        degradation.onStorage(StoragePolicy.action(free), free)
    }

    /** Recursively sum trip artifact sizes (bytes only — never reads content). */
    private fun tripsBytes(): Long {
        var total = 0L
        tripsRoot.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total
    }

    private companion object {
        const val CHECK_INTERVAL_MS = 30_000L
    }
}
