package com.wuxianggujun.tinaide.core.packages

import android.content.Context
import android.os.Build
import com.wuxianggujun.tinaide.core.common.registry.RegistryPackageId
import com.wuxianggujun.tinaide.core.network.ApiResult
import com.wuxianggujun.tinaide.core.packages.api.PackageApiClient
import com.wuxianggujun.tinaide.core.packages.backend.ApkPackageBackend
import com.wuxianggujun.tinaide.core.packages.backend.DownloadPackageBackend
import com.wuxianggujun.tinaide.core.packages.cache.PackageCacheManager
import com.wuxianggujun.tinaide.core.packages.model.*
import com.wuxianggujun.tinaide.core.packages.store.LocalInstallStateStore
import com.wuxianggujun.tinaide.core.proot.PRootEnvironment
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

class PackageManagerImpl(
    private val context: Context,
    private val apiClient: PackageApiClient,
    private val installStateStore: LocalInstallStateStore,
    private val cacheManager: PackageCacheManager = PackageCacheManager(context),
    private val prootEnv: PRootEnvironment? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PackageManager {

    companion object {
        private const val TAG = "PackageManagerImpl"
    }

    private val linuxPackageBackend: ApkPackageBackend? by lazy {
        prootEnv?.let { ApkPackageBackend(it) }
    }

    private val downloadBackend: DownloadPackageBackend by lazy {
        DownloadPackageBackend(context, apiClient)
    }

    private val cachedVersions = ConcurrentHashMap<String, Map<Platform, Int>>()

    override suspend fun getAvailablePackages(
        page: Int,
        pageSize: Int,
        category: String?,
        platform: Platform?,
        search: String?
    ): Result<List<GUIPackage>> {
        return getAllAvailablePackages().map { catalog ->
            val filtered = catalog.filter { pkg ->
                (category.isNullOrBlank() || pkg.category == category) &&
                    (platform == null || pkg.platformPackage(platform) != null) &&
                    (search.isNullOrBlank() || pkg.id.contains(search, true) || pkg.name.contains(search, true) ||
                        pkg.description?.contains(search, true) == true)
            }
            val count = pageSize.coerceIn(1, 100)
            val offset = ((page.coerceAtLeast(1) - 1L) * count).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            filtered.drop(offset).take(count)
        }
    }

    override suspend fun getAllAvailablePackages(): Result<List<GUIPackage>> = withContext(ioDispatcher) {
        cacheManager.getPackages()?.let { cached ->
            return@withContext Result.success(markBundledPackages(filterValidPackages(cached, "cache")))
        }
        when (val result = apiClient.getCatalog()) {
            is ApiResult.Success -> {
                val packages = filterValidPackages(result.data, "registry")
                cacheManager.savePackages(packages)
                Result.success(markBundledPackages(packages))
            }
            is ApiResult.Error -> {
                Timber.tag(TAG).e("Failed to get packages: ${result.message}")
                Result.failure(Exception(result.message))
            }
            is ApiResult.NetworkError -> {
                Timber.tag(TAG).e("Network error getting packages: ${result.message}")
                Result.failure(Exception(result.message))
            }
        }
    }

    private fun markBundledPackages(packages: List<GUIPackage>): List<GUIPackage> = packages.map { pkg ->
        pkg.copy(isBundled = installStateStore.isBundledPackage(pkg.id))
    }

    override suspend fun getCategories(): Result<List<PackageCategory>> {
        cacheManager.getCategories()?.let { cached ->
            Timber.tag(TAG).d("Returning ${cached.size} categories from cache")
            return Result.success(cached)
        }

        return when (val result = apiClient.getCategories()) {
            is ApiResult.Success -> {
                cacheManager.saveCategories(result.data)
                Result.success(result.data)
            }
            is ApiResult.Error -> {
                cacheManager.getCategories()?.let { return Result.success(it) }
                Result.failure(Exception(result.message))
            }
            is ApiResult.NetworkError -> {
                cacheManager.getCategories()?.let { return Result.success(it) }
                Result.failure(Exception(result.message))
            }
        }
    }

    override suspend fun getPackageDetail(packageId: String): Result<GUIPackage> {
        invalidPackageIdError(packageId)?.let { return Result.failure(it) }
        getValidCachedPackageDetail(packageId)?.let { cached ->
            Timber.tag(TAG).d("Returning package detail from cache: $packageId")
            // 标记内置包
            val pkg = if (installStateStore.isBundledPackage(cached.id)) {
                cached.copy(isBundled = true)
            } else {
                cached
            }
            return Result.success(pkg)
        }

        return when (val result = apiClient.getPackageDetail(packageId)) {
            is ApiResult.Success -> {
                val pkg = result.data
                if (pkg.id != packageId || !RegistryPackageId.isValid(pkg.id)) {
                    return Result.failure(IllegalStateException("Registry package detail id does not match request: $packageId"))
                }
                // 标记内置包
                val pkgWithBundledFlag = if (installStateStore.isBundledPackage(pkg.id)) {
                    pkg.copy(isBundled = true)
                } else {
                    pkg
                }
                cacheManager.savePackageDetail(pkgWithBundledFlag)
                Result.success(pkgWithBundledFlag)
            }
            is ApiResult.Error -> {
                getValidCachedPackageDetail(packageId)?.let { return Result.success(it) }
                Result.failure(Exception(result.message))
            }
            is ApiResult.NetworkError -> {
                getValidCachedPackageDetail(packageId)?.let { return Result.success(it) }
                Result.failure(Exception(result.message))
            }
        }
    }

    override suspend fun getInstallState(packageId: String): PackageInstallState {
        RegistryPackageId.requireValid(packageId)
        return installStateStore.getInstallState(packageId)
    }

    override suspend fun previewInstallPlan(
        packageId: String,
        platform: Platform
    ): Result<PackageInstallPlan> {
        invalidPackageIdError(packageId)?.let { return Result.failure(it) }
        return resolveInstallPlan(packageId, platform).map { descriptors ->
            PackageInstallPlan(
                packageId = packageId,
                packageName = descriptors.firstOrNull { it.packageId == packageId }?.packageName ?: packageId,
                platform = platform,
                packages = descriptors.map { descriptor ->
                    PackageInstallPlanItem(
                        packageId = descriptor.packageId,
                        packageName = descriptor.packageName,
                        version = descriptor.platformPackage.version,
                        isRoot = descriptor.packageId == packageId,
                        isAlreadyInstalled = isUsableInstallation(
                            descriptor.packageId,
                            descriptor.platform
                        )
                    )
                }
            )
        }
    }

    override suspend fun install(
        packageId: String,
        platform: Platform,
        progress: (InstallProgressEvent) -> Unit
    ): InstallResult = withContext(ioDispatcher) {
        installOnIo(packageId, platform, progress)
    }

    private suspend fun installOnIo(
        packageId: String,
        platform: Platform,
        progress: (InstallProgressEvent) -> Unit,
    ): InstallResult {
        invalidPackageIdError(packageId)?.let { error ->
            val installError = InstallError.UnknownError(error.message ?: "Invalid package id")
            progress(InstallProgressEvent.Failed(installError))
            return InstallResult.Failure(packageId, installError)
        }
        progress(InstallProgressEvent.Preparing("Resolving package dependencies..."))

        val plan = resolveInstallPlan(packageId, platform).getOrElse { error ->
            val installError = InstallError.UnknownError(error.message ?: "Failed to resolve package dependencies")
            progress(InstallProgressEvent.Failed(installError))
            return InstallResult.Failure(packageId, installError)
        }

        var rootResult: InstallResult.Success? = null
        for (descriptor in plan) {
            if (plan.size > 1) {
                val message = if (descriptor.packageId == packageId) {
                    "Installing ${descriptor.packageName}..."
                } else {
                    "Installing dependency ${descriptor.packageName}..."
                }
                progress(InstallProgressEvent.Preparing(message))
            }

            when (val result = installResolvedPackage(descriptor, progress)) {
                is InstallResult.Success -> {
                    if (descriptor.packageId == packageId) {
                        rootResult = result
                    }
                }
                is InstallResult.Failure -> return result
            }
        }

        return rootResult ?: InstallResult.Success(
            packageId = packageId,
            version = plan.lastOrNull { it.packageId == packageId }?.platformPackage?.version.orEmpty(),
            platform = platform
        )
    }

    private suspend fun resolveInstallPlan(
        packageId: String,
        platform: Platform
    ): Result<List<PackageInstallDescriptor>> = PackageDependencyResolver.resolveInstallPlan(
        rootPackageId = packageId,
        platform = platform,
        loadPackage = { dependencyId -> resolveInstallDescriptor(dependencyId, platform) },
        isInstalled = ::isUsableInstallation
    )

    private fun isUsableInstallation(packageId: String, platform: Platform): Boolean {
        if (!installStateStore.isInstalled(packageId, platform)) return false
        if (platform != Platform.ANDROID || packageId != "raylib") return true
        val revision = InstalledPackageMetadataReader.read(context, packageId)?.packageRevision ?: 0
        return revision >= RaylibRuntimePolicy.MINIMUM_PACKAGE_REVISION
    }

    private suspend fun resolveInstallDescriptor(
        packageId: String,
        platform: Platform
    ): Result<PackageInstallDescriptor> {
        val packageResult = getPackageDetail(packageId)
        if (packageResult.isFailure) {
            return Result.failure(packageResult.exceptionOrNull() ?: IllegalStateException("Unknown error"))
        }

        val pkg = packageResult.getOrNull()!!
        val platformPkg = pkg.platformPackage(platform)
            ?: return Result.failure(IllegalStateException("Package not available for $platform"))

        return Result.success(
            PackageInstallDescriptor(
                packageId = packageId,
                packageName = pkg.name,
                platform = platform,
                platformPackage = platformPkg,
                dependencies = resolveDeclaredDependencies(packageId, platform, platformPkg)
            )
        )
    }

    private suspend fun resolveDeclaredDependencies(
        packageId: String,
        platform: Platform,
        platformPkg: PlatformPackage
    ): List<String> {
        val dependencies = platformPkg.dependencies?.normalizedPackageDependencies()
            ?: getLatestVersion(packageId, platform)?.dependencies.normalizedPackageDependencies()
        val invalidDependencies = dependencies.filterNot(RegistryPackageId::isValid)
        check(invalidDependencies.isEmpty()) {
            "Package $packageId declares invalid dependencies: ${invalidDependencies.joinToString(", ")}"
        }
        return dependencies
    }

    private suspend fun installResolvedPackage(
        descriptor: PackageInstallDescriptor,
        progress: (InstallProgressEvent) -> Unit
    ): InstallResult {
        val packageId = descriptor.packageId
        val platform = descriptor.platform
        val platformPkg = descriptor.platformPackage
        val currentAppAbi = PackageAbiCompatibility.currentAppAbi(
            nativeLibraryDir = context.applicationInfo.nativeLibraryDir,
            supportedAbis = Build.SUPPORTED_ABIS,
        )

        if (
            platform == Platform.ANDROID &&
            !PackageAbiCompatibility.isCompatible(platformPkg.abi, arrayOf(currentAppAbi))
        ) {
            val error = InstallError.UnsupportedAbi(
                currentAbi = currentAppAbi,
                supportedAbis = platformPkg.abi.orEmpty()
            )
            progress(InstallProgressEvent.Failed(error))
            return InstallResult.Failure(packageId, error)
        }

        val result = when (platformPkg.installType) {
            InstallType.APT -> installLinuxPackage(packageId, platformPkg, progress)
            InstallType.DOWNLOAD -> installDownload(packageId, descriptor.packageName, platform, platformPkg, progress)
            InstallType.SCRIPT -> {
                val error = InstallError.UnknownError(
                    "Remote script packages are disabled because registry scripts are not signed",
                )
                progress(InstallProgressEvent.Failed(error))
                InstallResult.Failure(packageId, error)
            }
        }

        when (result) {
            is InstallResult.Success -> {
                installStateStore.clearUpdateAvailable(packageId, platform)
                installStateStore.setInstalled(
                    packageId = packageId,
                    platform = platform,
                    version = platformPkg.version,
                    packageName = descriptor.packageName,
                    installType = platformPkg.installType
                )
                PackageDependencyEvents.notifyChanged(
                    PackageDependencyEvents.DependencyChangedEvent(
                        packageId = packageId,
                        platform = platform,
                        action = PackageDependencyEvents.ChangeAction.INSTALLED,
                        version = platformPkg.version
                    )
                )
            }
            is InstallResult.Failure -> Unit
        }

        return result
    }

    private suspend fun installLinuxPackage(
        packageId: String,
        platformPkg: PlatformPackage,
        progress: (InstallProgressEvent) -> Unit
    ): InstallResult {
        val systemPackage = platformPkg.aptPackage
            ?: return InstallResult.Failure(packageId, InstallError.UnknownError("No Linux package specified"))

        val backend = linuxPackageBackend
        if (backend == null) {
            val error = InstallError.UnknownError("PRoot environment not available")
            progress(InstallProgressEvent.Failed(error))
            return InstallResult.Failure(packageId, error)
        }

        return backend.install(
            packageId = packageId,
            systemPackage = systemPackage,
            version = platformPkg.version,
            progress = progress
        )
    }

    private suspend fun installDownload(
        packageId: String,
        packageName: String,
        platform: Platform,
        platformPkg: PlatformPackage,
        progress: (InstallProgressEvent) -> Unit
    ): InstallResult {
        val versionId = getVersionId(packageId, platform)
        if (versionId == null) {
            val error = InstallError.UnknownError("Could not find version ID for $packageId on $platform")
            progress(InstallProgressEvent.Failed(error))
            return InstallResult.Failure(packageId, error)
        }

        return downloadBackend.install(
            packageId = packageId,
            versionId = versionId,
            version = platformPkg.version,
            progress = progress
        )
    }

    private suspend fun getVersionId(packageId: String, platform: Platform): Int? {
        cachedVersions[packageId]?.get(platform)?.let { return it }

        val latestVersion = getLatestVersion(packageId, platform) ?: return null
        return latestVersion.id
    }

    private suspend fun getLatestVersion(packageId: String, platform: Platform): PackageVersion? {
        if (!RegistryPackageId.isValid(packageId)) return null
        val versionsResult = apiClient.getPackageVersions(packageId)
        if (versionsResult !is ApiResult.Success) {
            return null
        }

        val versions = when (platform) {
            Platform.LINUX -> versionsResult.data.linux
            Platform.ANDROID -> versionsResult.data.android
        }?.filter { it.packageId == packageId } ?: return null

        val latestVersion = versions.find { it.isLatest } ?: versions.firstOrNull() ?: return null

        val versionMap = mutableMapOf<Platform, Int>()
        versionsResult.data.linux
            ?.find { it.packageId == packageId && it.isLatest }
            ?.let { versionMap[Platform.LINUX] = it.id }
        versionsResult.data.android
            ?.find { it.packageId == packageId && it.isLatest }
            ?.let { versionMap[Platform.ANDROID] = it.id }
        cachedVersions[packageId] = versionMap

        return latestVersion
    }

    private suspend fun installScript(
        packageId: String,
        platform: Platform,
        platformPkg: PlatformPackage,
        progress: (InstallProgressEvent) -> Unit
    ): InstallResult {
        progress(InstallProgressEvent.Installing("Running install script..."))

        val proot = prootEnv
        if (proot == null || !proot.isInstalled()) {
            val error = InstallError.UnknownError("PRoot environment not available")
            progress(InstallProgressEvent.Failed(error))
            return InstallResult.Failure(packageId, error)
        }

        val versionsResult = apiClient.getPackageVersions(packageId)
        if (versionsResult !is ApiResult.Success) {
            val error = InstallError.UnknownError("Failed to get package versions")
            progress(InstallProgressEvent.Failed(error))
            return InstallResult.Failure(packageId, error)
        }

        val versions = when (platform) {
            Platform.LINUX -> versionsResult.data.linux
            Platform.ANDROID -> versionsResult.data.android
        }
        val version = versions?.find { it.isLatest } ?: versions?.firstOrNull()

        val installScript = version?.installScript
        if (installScript.isNullOrBlank()) {
            val error = InstallError.UnknownError("No install script available")
            progress(InstallProgressEvent.Failed(error))
            return InstallResult.Failure(packageId, error)
        }

        Timber.tag(TAG).d("Executing install script for $packageId")

        val result = proot.executeShellWithEnv(
            command = installScript,
            env = mapOf("DEBIAN_FRONTEND" to "noninteractive"),
            timeout = 600_000
        )

        return if (result.exitCode == 0) {
            Timber.tag(TAG).d("Script install completed for $packageId")
            progress(InstallProgressEvent.Completed(InstallResult.Success(packageId, platformPkg.version, platform)))
            InstallResult.Success(packageId, platformPkg.version, platform)
        } else {
            val error = InstallError.ScriptError(result.exitCode, result.stderr.takeLast(500))
            Timber.tag(TAG).e("Script install failed: ${error.toDisplayMessage()}")
            progress(InstallProgressEvent.Failed(error))
            InstallResult.Failure(packageId, error)
        }
    }

    override suspend fun uninstall(packageId: String, platform: Platform): UninstallResult =
        withContext(ioDispatcher) {
            uninstallOnIo(packageId, platform)
        }

    private suspend fun uninstallOnIo(packageId: String, platform: Platform): UninstallResult {
        invalidPackageIdError(packageId)?.let { error ->
            return UninstallResult.Failure(
                packageId,
                UninstallError.UnknownError(error.message ?: "Invalid package id"),
            )
        }
        val state = installStateStore.getInstallState(packageId)
        val platformState = state.forPlatform(platform)

        if (platformState !is PlatformInstallState.Installed && platformState !is PlatformInstallState.UpdateAvailable) {
            return UninstallResult.Failure(packageId, UninstallError.PackageNotFound(packageId))
        }

        val packageResult = getPackageDetail(packageId)
        val pkg = packageResult.getOrNull()
        val platformPkg = when (platform) {
            Platform.LINUX -> pkg?.linux
            Platform.ANDROID -> pkg?.android
        }

        // 确定安装类型：优先使用 API 返回的，如果 API 不可用则从本地存储回退
        val effectiveInstallType = platformPkg?.installType
            ?: installStateStore.getAllInstalledPackages()
                .find { it.packageId == packageId && it.platform == platform }
                ?.installType

        val result = when (effectiveInstallType) {
            InstallType.APT -> {
                val systemPackage = platformPkg?.aptPackage ?: packageId
                linuxPackageBackend?.uninstall(packageId, systemPackage)
                    ?: UninstallResult.Failure(packageId, UninstallError.UnknownError("PRoot not available"))
            }
            InstallType.DOWNLOAD -> {
                downloadBackend.uninstall(packageId)
            }
            InstallType.SCRIPT -> UninstallResult.Failure(
                packageId,
                UninstallError.UnknownError("Remote script packages are disabled because registry scripts are not signed"),
            )
            null -> {
                // 安装类型未知（API 和本地都没有记录），尝试清理下载文件作为兜底
                Timber.tag(TAG).w("Unknown install type for $packageId, attempting download file cleanup")
                cleanupDownloadFiles(packageId)
                UninstallResult.Success(packageId, platform, 0)
            }
        }

        val uninstalledVersion = when (platformState) {
            is PlatformInstallState.Installed -> platformState.version
            is PlatformInstallState.UpdateAvailable -> platformState.currentVersion
            else -> null
        }
        when (result) {
            is UninstallResult.Success -> {
                installStateStore.setUninstalled(packageId, platform)
                PackageDependencyEvents.notifyChanged(
                    PackageDependencyEvents.DependencyChangedEvent(
                        packageId = packageId,
                        platform = platform,
                        action = PackageDependencyEvents.ChangeAction.UNINSTALLED,
                        version = uninstalledVersion
                    )
                )
            }
            is UninstallResult.Failure -> Unit
        }

        return result
    }

    /**
     * 清理下载类型包的解压文件。
     * 作为兜底机制，确保卸载时文件被实际删除。
     */
    private fun cleanupDownloadFiles(packageId: String) {
        try {
            val installPath = downloadBackend.getInstallPath(packageId)
            if (installPath.exists()) {
                val freedSpace = installPath.walkTopDown().sumOf { it.length() }
                installPath.deleteRecursively()
                Timber.tag(TAG).d("Cleaned up download files for $packageId, freed $freedSpace bytes")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to cleanup download files for $packageId")
        }
    }

    override suspend fun getInstalledPackages(): List<InstalledPackageInfo> = installStateStore.getAllInstalledPackages()

    override suspend fun getDependentPackages(packageId: String, platform: Platform): List<String> {
        RegistryPackageId.requireValid(packageId)
        val installedPackageIds = installStateStore.getAllInstalledPackages()
            .asSequence()
            .filter { it.platform == platform }
            .map { it.packageId }
            .toSet()
        if (installedPackageIds.isEmpty()) return emptyList()

        val packages = getAllAvailablePackages()
            .getOrElse { error ->
                Timber.tag(TAG).w(error, "Failed to resolve dependent packages for %s", packageId)
                return emptyList()
            }
            .filter { it.id in installedPackageIds }
            .map { summary ->
                getPackageDetail(summary.id).getOrNull() ?: summary
            }

        return PackageDependencyResolver.findDirectDependents(
            packageId = packageId,
            platform = platform,
            packages = packages,
            dependenciesForPackage = { pkg, platformPkg ->
                try {
                    resolveDeclaredDependencies(pkg.id, platform, platformPkg)
                } catch (error: Throwable) {
                    Timber.tag(TAG).w(error, "Failed to read dependencies for %s", pkg.id)
                    emptyList()
                }
            }
        )
    }

    override suspend fun refreshCache(): Unit = withContext(ioDispatcher) {
        apiClient.invalidateCache()
        // Package detail entries can otherwise survive a refresh for seven days.
        cacheManager.clearCache()
        cachedVersions.clear()
    }

    override suspend fun clearCache(): Unit = withContext(ioDispatcher) {
        apiClient.invalidateCache()
        cacheManager.clearCache()
        cachedVersions.clear()
        downloadBackend.clearDownloadCache()
    }

    override suspend fun checkForUpdates(): Map<PackagePlatformKey, UpdateInfo> = withContext(ioDispatcher) {
        apiClient.invalidateCache()
        val updates = mutableMapOf<PackagePlatformKey, UpdateInfo>()
        val installedPackages = installStateStore.getAllInstalledPackages()

        for (installed in installedPackages) {
            if (!RegistryPackageId.isValid(installed.packageId)) {
                Timber.tag(TAG).w("Ignoring invalid installed package id")
                continue
            }
            try {
                val detailResult = apiClient.getPackageDetail(installed.packageId)
                if (detailResult is ApiResult.Success) {
                    val pkg = detailResult.data
                    if (pkg.id != installed.packageId) continue
                    // Installing an announced revision must not reuse a cached older URL/hash.
                    cacheManager.savePackageDetail(pkg)
                    cachedVersions.remove(installed.packageId)

                    if (installed.platform == Platform.LINUX) {
                        pkg.linux?.let { platformPkg ->
                            if (isNewerPackageVersion(platformPkg.version, installed.version)) {
                                updates[PackagePlatformKey(installed.packageId, installed.platform)] = UpdateInfo(
                                    packageId = installed.packageId,
                                    packageName = pkg.name,
                                    platform = Platform.LINUX,
                                    currentVersion = installed.version,
                                    newVersion = platformPkg.version
                                )
                            } else {
                                installStateStore.clearUpdateAvailable(installed.packageId, installed.platform)
                            }
                        }
                    }

                    if (installed.platform == Platform.ANDROID) {
                        pkg.android?.let { platformPkg ->
                            val metadata = InstalledPackageMetadataReader.read(context, installed.packageId)
                            val localRevision = metadata?.takeIf { it.version == installed.version }?.packageRevision
                            if (isNewerPackageVersion(
                                    platformPkg.version,
                                    installed.version,
                                    platformPkg.packageRevision,
                                    localRevision,
                                )
                            ) {
                                updates[PackagePlatformKey(installed.packageId, installed.platform)] = UpdateInfo(
                                    packageId = installed.packageId,
                                    packageName = pkg.name,
                                    platform = Platform.ANDROID,
                                    currentVersion = installed.version,
                                    newVersion = platformPkg.version
                                )
                            } else {
                                installStateStore.clearUpdateAvailable(installed.packageId, installed.platform)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.tag(TAG).w(e, "Failed to check update for ${installed.packageId}")
            }
        }

        for (updateInfo in updates.values) {
            installStateStore.setUpdateAvailable(
                packageId = updateInfo.packageId,
                platform = updateInfo.platform,
                currentVersion = updateInfo.currentVersion,
                newVersion = updateInfo.newVersion
            )
        }

        updates
    }

    private fun filterValidPackages(packages: List<GUIPackage>, source: String): List<GUIPackage> =
        packages.filter { pkg ->
            val valid = RegistryPackageId.isValid(pkg.id)
            if (!valid) Timber.tag(TAG).w("Ignoring invalid package id from $source")
            valid
        }

    private fun invalidPackageIdError(packageId: String): IllegalArgumentException? =
        if (RegistryPackageId.isValid(packageId)) {
            null
        } else {
            IllegalArgumentException("Invalid package id: $packageId")
        }

    private fun getValidCachedPackageDetail(packageId: String): GUIPackage? =
        cacheManager.getPackageDetail(packageId)?.takeIf { cached ->
            cached.id == packageId && RegistryPackageId.isValid(cached.id)
        }
}
