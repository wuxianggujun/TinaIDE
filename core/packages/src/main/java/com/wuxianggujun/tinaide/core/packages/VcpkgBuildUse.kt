package com.wuxianggujun.tinaide.core.packages

import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import timber.log.Timber

/** All vcpkg consumers are started by the IDE host. A build pins one shared generation per ABI. */
object VcpkgBuildUse {
    private class Use : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Use>
        val roots = mutableMapOf<File, File>()
    }

    private val monitor = Any()
    private var activeBuilds = 0
    private val pending = mutableMapOf<File, suspend () -> Unit>()

    suspend fun <T> during(enabled: Boolean, block: suspend () -> T): T {
        if (!enabled || currentCoroutineContext()[Use] != null) return block()
        synchronized(monitor) { activeBuilds++ }
        return try {
            withContext(Use()) { block() }
        } finally {
            val cleanup = synchronized(monitor) {
                activeBuilds--
                if (activeBuilds == 0) pending.values.toList().also { pending.clear() } else emptyList()
            }
            if (cleanup.isNotEmpty()) withContext(NonCancellable + Dispatchers.IO) {
                for (collect in cleanup) {
                    try { collect() } catch (error: Exception) {
                        Timber.tag("VcpkgStorage").w(error, "Deferred snapshot cleanup failed")
                    }
                }
            }
        }
    }

    internal suspend fun sharedRoot(key: File, resolve: () -> File): File {
        val use = currentCoroutineContext()[Use] ?: return resolve()
        return synchronized(monitor) { use.roots.getOrPut(key, resolve) }
    }

    /** Caller holds the store's publication lock. Keep slow directory removal outside the monitor. */
    internal fun retireWhenIdle(store: File, obsolete: List<File>, retryWithFreshReferences: suspend () -> Unit) {
        if (obsolete.isEmpty()) return
        val retired = synchronized(monitor) {
            if (activeBuilds != 0) {
                pending[store] = retryWithFreshReferences
                return
            }
            obsolete.distinct().mapNotNull { old ->
                if (Files.isSymbolicLink(old.toPath())) return@mapNotNull null
                if (old.name.startsWith(".retired-")) return@mapNotNull old
                val trash = File(old.parentFile, ".retired-${UUID.randomUUID()}")
                if (old.renameTo(trash)) trash else {
                    Timber.tag("VcpkgStorage").w("Could not retire %s", old.name)
                    null
                }
            }
        }
        retired.forEach {
            // Source trees may contain Git metadata. Never follow a symlink while reclaiming a tree.
            val root = it.toPath()
            try {
                Files.walk(root).use { entries ->
                    entries.sorted(Comparator.reverseOrder()).forEach { path -> Files.deleteIfExists(path) }
                }
            } catch (error: Exception) {
                Timber.tag("VcpkgStorage").w(error, "Could not finish removing a retired snapshot")
            }
        }
    }
}
