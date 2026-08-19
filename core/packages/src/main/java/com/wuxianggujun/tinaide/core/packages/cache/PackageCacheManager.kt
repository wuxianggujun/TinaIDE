package com.wuxianggujun.tinaide.core.packages.cache

import android.content.Context
import com.wuxianggujun.tinaide.core.common.registry.RegistryPackageId
import com.wuxianggujun.tinaide.core.packages.model.GUIPackage
import com.wuxianggujun.tinaide.core.packages.model.PackageCategory
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import java.io.File
import kotlinx.serialization.Serializable
import timber.log.Timber

class PackageCacheManager(
    context: Context,
    private val config: CacheConfig = CacheConfig()
) {
    companion object {
        private const val TAG = "PackageCacheManager"
        private const val PACKAGES_CACHE_FILE = "packages.json"
        private const val CATEGORIES_CACHE_FILE = "categories.json"
        private const val CACHE_META_FILE = "cache_meta.json"
    }

    private val cacheDir: File = File(context.cacheDir, "package_registry").also { it.mkdirs() }
    private val json = JsonSerializer.pretty

    private val packagesFile: File get() = File(cacheDir, PACKAGES_CACHE_FILE)
    private val categoriesFile: File get() = File(cacheDir, CATEGORIES_CACHE_FILE)
    private val metaFile: File get() = File(cacheDir, CACHE_META_FILE)

    fun getPackages(): List<GUIPackage>? {
        if (!isCacheValid(packagesFile)) {
            Timber.tag(TAG).d("Packages cache invalid or expired")
            return null
        }

        return try {
            val parsed = JsonSerializer.decodeFromFileOrNull<List<GUIPackage>>(packagesFile)
            if (parsed != null) {
                Timber.tag(TAG).d("Loaded ${parsed.size} packages from cache")
            }
            parsed
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to load packages from cache")
            null
        }
    }

    fun savePackages(packages: List<GUIPackage>) {
        try {
            JsonSerializer.encodePrettyToFile(packagesFile, packages)
            updateCacheMeta(PACKAGES_CACHE_FILE)
            Timber.tag(TAG).d("Saved ${packages.size} packages to cache")

            if (config.autoCleanup) {
                cleanupIfNeeded()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to save packages to cache")
        }
    }

    fun getCategories(): List<PackageCategory>? {
        if (!isCacheValid(categoriesFile)) {
            Timber.tag(TAG).d("Categories cache invalid or expired")
            return null
        }

        return try {
            val parsed = JsonSerializer.decodeFromFileOrNull<List<PackageCategory>>(categoriesFile)
            if (parsed != null) {
                Timber.tag(TAG).d("Loaded ${parsed.size} categories from cache")
            }
            parsed
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to load categories from cache")
            null
        }
    }

    fun saveCategories(categories: List<PackageCategory>) {
        try {
            JsonSerializer.encodePrettyToFile(categoriesFile, categories)
            updateCacheMeta(CATEGORIES_CACHE_FILE)
            Timber.tag(TAG).d("Saved ${categories.size} categories to cache")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to save categories to cache")
        }
    }

    fun getPackageDetail(packageId: String): GUIPackage? {
        if (!RegistryPackageId.isValid(packageId)) return null
        val detailFile = File(cacheDir, "package_$packageId.json")
        if (!isCacheValid(detailFile, config.packageExpiry)) {
            return null
        }

        return try {
            JsonSerializer.decodeFromFileOrNull<GUIPackage>(detailFile)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to load package detail from cache: $packageId")
            null
        }
    }

    fun savePackageDetail(pkg: GUIPackage) {
        if (!RegistryPackageId.isValid(pkg.id)) {
            Timber.tag(TAG).w("Ignoring package detail with invalid id")
            return
        }
        try {
            val detailFile = File(cacheDir, "package_${pkg.id}.json")
            JsonSerializer.encodePrettyToFile(detailFile, pkg)
            updateCacheMeta("package_${pkg.id}.json")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to save package detail to cache: ${pkg.id}")
        }
    }

    private fun isCacheValid(file: File, expiry: Long = config.registryExpiry): Boolean {
        if (!file.exists()) return false
        val lastModified = file.lastModified()
        val age = System.currentTimeMillis() - lastModified
        return age < expiry
    }

    private fun updateCacheMeta(fileName: String) {
        try {
            val meta = loadCacheMeta().toMutableMap()
            meta[fileName] = System.currentTimeMillis()
            JsonSerializer.encodePrettyToFile(metaFile, meta)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to update cache meta")
        }
    }

    private fun loadCacheMeta(): Map<String, Long> = try {
        if (metaFile.exists()) {
            JsonSerializer.decodeFromFileOrNull<Map<String, Long>>(metaFile) ?: emptyMap()
        } else {
            emptyMap()
        }
    } catch (e: Exception) {
        emptyMap()
    }

    private fun cleanupIfNeeded() {
        val currentSize = getCacheSize()
        if (currentSize > config.maxCacheSize) {
            Timber.tag(TAG).d("Cache size ($currentSize) exceeds limit (${config.maxCacheSize}), cleaning up...")
            cleanupOldFiles(currentSize - config.maxCacheSize / 2)
        }
    }

    private fun cleanupOldFiles(bytesToFree: Long) {
        val files = cacheDir.listFiles()
            ?.filter { it.name.startsWith("package_") && it.name.endsWith(".json") }
            ?.sortedBy { it.lastModified() }
            ?: return

        var freed = 0L
        for (file in files) {
            if (freed >= bytesToFree) break
            freed += file.length()
            file.delete()
            Timber.tag(TAG).d("Deleted old cache file: ${file.name}")
        }
    }

    fun getCacheSize(): Long = cacheDir.walkTopDown().sumOf { it.length() }

    fun getCacheInfo(): CacheInfo = CacheInfo(
        totalSize = getCacheSize(),
        packagesLastUpdated = if (packagesFile.exists()) packagesFile.lastModified() else null,
        categoriesLastUpdated = if (categoriesFile.exists()) categoriesFile.lastModified() else null,
        isPackagesCacheValid = isCacheValid(packagesFile),
        isCategoriesCacheValid = isCacheValid(categoriesFile)
    )

    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
        Timber.tag(TAG).d("Cache cleared")
    }

    fun invalidatePackages() {
        if (packagesFile.exists()) {
            packagesFile.delete()
        }
    }

    fun invalidateCategories() {
        if (categoriesFile.exists()) {
            categoriesFile.delete()
        }
    }
}

@Serializable
data class CacheConfig(
    val maxCacheSize: Long = 50 * 1024 * 1024,
    val registryExpiry: Long = 24 * 60 * 60 * 1000L,
    val packageExpiry: Long = 7 * 24 * 60 * 60 * 1000L,
    val autoCleanup: Boolean = true
)

@Serializable
data class CacheInfo(
    val totalSize: Long,
    val packagesLastUpdated: Long? = null,
    val categoriesLastUpdated: Long? = null,
    val isPackagesCacheValid: Boolean,
    val isCategoriesCacheValid: Boolean
) {
    val totalSizeFormatted: String
        get() = when {
            totalSize >= 1024 * 1024 -> "%.1f MB".format(totalSize / (1024.0 * 1024.0))
            totalSize >= 1024 -> "%.1f KB".format(totalSize / 1024.0)
            else -> "$totalSize B"
        }
}
