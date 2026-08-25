package com.wuxianggujun.tinaide.core.proot

import android.content.Context
import com.wuxianggujun.tinaide.core.linuxdistro.AndroidAssetLinuxDistroManifestSource
import com.wuxianggujun.tinaide.core.linuxdistro.LinuxDistroCatalog
import com.wuxianggujun.tinaide.core.linuxdistro.LinuxDistroManifest
import com.wuxianggujun.tinaide.core.linuxdistro.LinuxDistroManifestParser
import com.wuxianggujun.tinaide.core.linuxdistro.LinuxDistroManifestSource
import com.wuxianggujun.tinaide.core.linuxdistro.loadCatalog
import java.io.File
import timber.log.Timber

/**
 * Centralizes linux distro catalog loading policy.
 *
 * Startup callers must use [loadBundledCatalog] or [loadCachedOrBundledCatalog].
 * Network-backed refresh is explicit and isolated in [loadRemoteOrCachedCatalog].
 */
class LinuxDistroCatalogRepository(
    private val bundledSource: LinuxDistroManifestSource,
    private val cachedSource: LinuxDistroManifestSource,
    private val remoteSource: LinuxDistroManifestSource,
) {
    fun loadBundledCatalog(): LinuxDistroCatalog = bundledSource.loadCatalog()

    fun loadCachedOrBundledCatalog(): LinuxDistroCatalog = cachedSource.loadCatalog()

    fun loadRemoteOrCachedCatalog(): LinuxDistroCatalog = remoteSource.loadCatalog()

    companion object {
        fun create(context: Context): LinuxDistroCatalogRepository {
            val appContext = context.applicationContext
            val bundledSource = AndroidAssetLinuxDistroManifestSource(appContext)
            // Remote manifests are not consumed until the registry publishes a signed manifest.
            // Existing caches were created from unsigned data and are intentionally ignored.
            val cachedSource = bundledSource
            val remoteSource = bundledSource
            return LinuxDistroCatalogRepository(
                bundledSource = bundledSource,
                cachedSource = cachedSource,
                remoteSource = remoteSource,
            )
        }
    }
}

class CachedLinuxDistroManifestSource(
    private val cacheFile: File,
    private val fallback: LinuxDistroManifestSource,
) : LinuxDistroManifestSource {
    override fun loadManifest(): LinuxDistroManifest = readCacheOrNull() ?: fallback.loadManifest()

    private fun readCacheOrNull(): LinuxDistroManifest? {
        if (!cacheFile.isFile) return null
        return runCatching {
            LinuxDistroManifestParser.decode(cacheFile.readText(Charsets.UTF_8))
        }.onFailure { error ->
            Timber.tag(TAG).w(error, "Decode cached linux distro manifest failed")
        }.getOrNull()
    }

    private companion object {
        private const val TAG = "CachedLinuxManifest"
    }
}
