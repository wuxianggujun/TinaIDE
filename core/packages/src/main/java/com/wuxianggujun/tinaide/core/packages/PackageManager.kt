package com.wuxianggujun.tinaide.core.packages

import com.wuxianggujun.tinaide.core.packages.model.*

interface PackageManager {
    suspend fun getAvailablePackages(
        page: Int = 1,
        pageSize: Int = 50,
        category: String? = null,
        platform: Platform? = null,
        search: String? = null
    ): Result<List<GUIPackage>>

    suspend fun getCategories(): Result<List<PackageCategory>>

    suspend fun getPackageDetail(packageId: String): Result<GUIPackage>

    suspend fun getInstallState(packageId: String): PackageInstallState

    suspend fun previewInstallPlan(packageId: String, platform: Platform): Result<PackageInstallPlan>

    suspend fun install(
        packageId: String,
        platform: Platform,
        progress: (InstallProgressEvent) -> Unit = {}
    ): InstallResult

    suspend fun uninstall(
        packageId: String,
        platform: Platform
    ): UninstallResult

    suspend fun getInstalledPackages(): List<InstalledPackageInfo>

    suspend fun getDependentPackages(packageId: String, platform: Platform): List<String>

    suspend fun refreshCache()

    suspend fun clearCache()

    suspend fun checkForUpdates(): Map<PackagePlatformKey, UpdateInfo>

    /** Complete catalog for screens that search locally. */
    suspend fun getAllAvailablePackages(): Result<List<GUIPackage>> {
        val packages = linkedMapOf<String, GUIPackage>()
        var page = 1
        val pageSize = 100
        while (true) {
            val batch = getAvailablePackages(page = page++, pageSize = pageSize).getOrElse { return Result.failure(it) }
            val previousSize = packages.size
            batch.forEach { packages[it.id] = it }
            if (batch.size < pageSize) return Result.success(packages.values.toList())
            if (packages.size == previousSize) return Result.failure(IllegalStateException("Package pagination made no progress"))
        }
    }
}

data class PackagePlatformKey(val packageId: String, val platform: Platform)

data class InstalledPackageInfo(
    val packageId: String,
    val packageName: String,
    val platform: Platform,
    val version: String,
    val installedAt: Long,
    val installType: InstallType,
    val size: Long? = null
)

data class UpdateInfo(
    val packageId: String,
    val packageName: String,
    val platform: Platform,
    val currentVersion: String,
    val newVersion: String
)

data class PackageInstallPlan(
    val packageId: String,
    val packageName: String,
    val platform: Platform,
    val packages: List<PackageInstallPlanItem>
)

data class PackageInstallPlanItem(
    val packageId: String,
    val packageName: String,
    val version: String,
    val isRoot: Boolean,
    val isAlreadyInstalled: Boolean
)
