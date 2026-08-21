package com.wuxianggujun.tinaide.core.packages.backend

import android.content.Context
import android.os.Build
import com.wuxianggujun.tinaide.core.common.io.TarExtractor
import com.wuxianggujun.tinaide.core.common.registry.RegistryPackageId
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str
import com.wuxianggujun.tinaide.core.network.ApiResult
import com.wuxianggujun.tinaide.core.network.registry.GitHubRegistryHttpClientFactory
import com.wuxianggujun.tinaide.core.packages.InstalledPackageMetadata
import com.wuxianggujun.tinaide.core.packages.PackageAbiCompatibility
import com.wuxianggujun.tinaide.core.packages.PackageArchivePolicy
import com.wuxianggujun.tinaide.core.packages.PackageDownloadSourceSelector
import com.wuxianggujun.tinaide.core.packages.PackageInstallCoordinator
import com.wuxianggujun.tinaide.core.packages.api.PackageApiClient
import com.wuxianggujun.tinaide.core.packages.download.DownloadError
import com.wuxianggujun.tinaide.core.packages.download.DownloadResult
import com.wuxianggujun.tinaide.core.packages.download.ResumableDownloader
import com.wuxianggujun.tinaide.core.packages.model.*
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import java.io.File
import java.io.FileInputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

class DownloadPackageBackend(
    private val context: Context,
    private val apiClient: PackageApiClient
) {
    companion object {
        private const val TAG = "DownloadPackageBackend"
        private const val INSTALL_DIR_NAME = "installed-packages"
    }

    private enum class ArchiveFormat {
        ZIP,
        TAR
    }

    private data class ArchiveTarget(
        val file: File,
        val formatHint: ArchiveFormat?
    )

    private val downloadDir: File by lazy {
        File(context.cacheDir, "package_downloads").also { it.mkdirs() }
    }

    private val installDir: File by lazy {
        File(context.filesDir, INSTALL_DIR_NAME).also { it.mkdirs() }
    }

    private val downloader: ResumableDownloader by lazy {
        ResumableDownloader(
            downloadDir = downloadDir,
            client = GitHubRegistryHttpClientFactory.download(context.applicationContext),
        )
    }

    private val downloadCacheMutex = Mutex()

    suspend fun install(
        packageId: String,
        versionId: Int,
        version: String,
        progress: (InstallProgressEvent) -> Unit,
    ): InstallResult = downloadCacheMutex.withLock {
        installWithCacheLock(packageId, versionId, version, progress)
    }

    private suspend fun installWithCacheLock(
        packageId: String,
        versionId: Int,
        version: String,
        progress: (InstallProgressEvent) -> Unit,
    ): InstallResult {
        if (!RegistryPackageId.isValid(packageId)) {
            val error = InstallError.UnknownError("Invalid package id: $packageId")
            progress(InstallProgressEvent.Failed(error))
            return InstallResult.Failure(packageId, error)
        }
        progress(InstallProgressEvent.Preparing("Fetching download info..."))

        val downloadInfoResult = apiClient.getDownloadInfo(packageId, versionId)
        if (downloadInfoResult !is ApiResult.Success) {
            val error = InstallError.NetworkError(
                downloadInfoResult.getErrorMessage() ?: "Failed to get download info"
            )
            progress(InstallProgressEvent.Failed(error))
            return InstallResult.Failure(packageId, error)
        }

        val downloadInfo = downloadInfoResult.data
        if (
            downloadInfo.packageId != packageId ||
            downloadInfo.version != version ||
            downloadInfo.platform != Platform.ANDROID
        ) {
            val error = InstallError.UnknownError("Registry download metadata does not match the requested package")
            progress(InstallProgressEvent.Failed(error))
            return InstallResult.Failure(packageId, error)
        }
        val checksum = downloadInfo.checksum
        if (checksum == null || !checksum.matches(Regex("(?i)^sha256:[0-9a-f]{64}$"))) {
            val error = InstallError.UnknownError("Package download requires a valid SHA-256 checksum")
            progress(InstallProgressEvent.Failed(error))
            return InstallResult.Failure(packageId, error)
        }
        val targetAbi = PackageAbiCompatibility.currentAppAbi(
            nativeLibraryDir = context.applicationInfo.nativeLibraryDir,
            supportedAbis = Build.SUPPORTED_ABIS,
        )
        val sortedSources = PackageDownloadSourceSelector.select(downloadInfo.sources, targetAbi)
            .filter { source -> runCatching { URI(source.url).scheme.equals("https", ignoreCase = true) }.getOrDefault(false) }
        if (sortedSources.isEmpty()) {
            val error = InstallError.UnknownError(Strings.pkg_manager_error_no_download_source_for_abi.str(targetAbi))
            progress(InstallProgressEvent.Failed(error))
            return InstallResult.Failure(packageId, error)
        }

        var lastError: DownloadError? = null

        for (source in sortedSources) {
            val archiveTarget = buildArchiveTarget(
                packageId,
                version,
                source.abi,
                source.url,
                PackageInstallCoordinator.createOperationId(),
            )
            Timber.tag(TAG).d("Trying source: ${source.name}, abi=${source.abi ?: "legacy"}")
            progress(InstallProgressEvent.Preparing("Downloading from ${source.name}..."))

            val downloadResult = downloader.download(
                url = source.url,
                targetFile = archiveTarget.file,
                checksum = source.checksum ?: downloadInfo.checksum,
                expectedSize = source.size ?: downloadInfo.size,
                supportsRange = source.supportsRange
            ) { downloaded, total, speed ->
                progress(InstallProgressEvent.Downloading(downloaded, total, speed))
            }

            when (downloadResult) {
                is DownloadResult.Success -> {
                    val archiveFormat = detectArchiveFormat(downloadResult.file, archiveTarget.formatHint)
                    if (archiveFormat == null) {
                        lastError = DownloadError.IOError("Unsupported package archive format")
                        Timber.tag(TAG).w("Unsupported archive file: ${downloadResult.file.name}")
                        downloadResult.file.delete()
                        continue
                    }

                    progress(InstallProgressEvent.Verifying("Download complete, extracting..."))

                    val extractResult = extractPackage(
                        archiveFile = downloadResult.file,
                        archiveFormat = archiveFormat,
                        packageId = packageId,
                        expectedVersion = version,
                        progress = progress
                    )
                    downloadResult.file.delete()

                    if (extractResult.isSuccess) {
                        progress(
                            InstallProgressEvent.Completed(
                                InstallResult.Success(packageId, version, Platform.ANDROID)
                            )
                        )
                        return InstallResult.Success(packageId, version, Platform.ANDROID)
                    } else {
                        val error = InstallError.ExtractionFailed(
                            extractResult.exceptionOrNull()?.message ?: "Extraction failed"
                        )
                        progress(InstallProgressEvent.Failed(error))
                        return InstallResult.Failure(packageId, error)
                    }
                }
                is DownloadResult.Failed -> {
                    lastError = downloadResult.error
                    Timber.tag(TAG).w("Download from ${source.name} failed: ${downloadResult.error.toDisplayMessage()}")
                    continue
                }
                is DownloadResult.Cancelled -> {
                    val error = InstallError.Cancelled
                    progress(InstallProgressEvent.Failed(error))
                    return InstallResult.Failure(packageId, error)
                }
            }
        }

        val error = when (lastError) {
            is DownloadError.HttpError -> InstallError.NetworkError("HTTP ${lastError.code}: ${lastError.message}")
            is DownloadError.SizeMismatch -> InstallError.SizeMismatch(lastError.expected, lastError.actual)
            is DownloadError.ChecksumMismatch -> InstallError.ChecksumMismatch(lastError.expected, lastError.actual)
            is DownloadError.SizeLimitExceeded -> InstallError.SizeMismatch(lastError.limit, lastError.actual)
            is DownloadError.IOError -> InstallError.NetworkError(lastError.message)
            null -> InstallError.UnknownError("All download sources failed")
        }
        progress(InstallProgressEvent.Failed(error))
        return InstallResult.Failure(packageId, error)
    }

    private suspend fun extractPackage(
        archiveFile: File,
        archiveFormat: ArchiveFormat,
        packageId: String,
        expectedVersion: String,
        progress: (InstallProgressEvent) -> Unit
    ): Result<Unit> = runCatching {
        PackageInstallCoordinator.withExclusiveAccess(installDir) {
            cleanupInterruptedInstallDirectories()
            val operationId = PackageInstallCoordinator.createOperationId()
            val targetDir = resolvePackageDirectory(packageId)
            val stagingDir = PackageInstallCoordinator.resolveTransactionDirectory(
                installDir,
                PackageInstallCoordinator.DOWNLOAD_STAGING_PREFIX,
                packageId,
                operationId,
            )

            check(stagingDir.mkdirs()) { "Failed to create package staging directory" }
            try {
                when (archiveFormat) {
                    ArchiveFormat.ZIP -> PackageArchivePolicy.extractZip(archiveFile, stagingDir) { pct ->
                        progress(InstallProgressEvent.Extracting(pct))
                    }
                    ArchiveFormat.TAR -> {
                        TarExtractor.extract(
                            archiveFile,
                            stagingDir,
                            limits = PackageArchivePolicy.limits,
                        ) { pct ->
                            progress(InstallProgressEvent.Extracting(pct))
                        }
                    }
                }

                requireValidPackageMetadata(stagingDir, packageId, expectedVersion)
                replaceDirectoryAtomically(stagingDir, targetDir, packageId, operationId)
                Timber.tag(TAG).d("Published package to ${targetDir.absolutePath}")
            } finally {
                if (stagingDir.exists()) stagingDir.deleteRecursively()
            }
        }
    }.onFailure { error ->
        Timber.tag(TAG).e(error, "Extraction failed for %s", packageId)
    }

    private fun buildArchiveTarget(
        packageId: String,
        version: String,
        abi: String?,
        url: String,
        operationId: String,
    ): ArchiveTarget {
        val rawPath = runCatching { URI(url).path }.getOrNull().orEmpty()
        val decodedPath = runCatching {
            URLDecoder.decode(rawPath.ifBlank { url.substringBefore('?') }, StandardCharsets.UTF_8.name())
        }.getOrDefault(rawPath.ifBlank { url.substringBefore('?') })
        val lowerPath = decodedPath.lowercase()

        val suffixAndFormat = when {
            lowerPath.endsWith(".tar.xz") -> ".tar.xz" to ArchiveFormat.TAR
            lowerPath.endsWith(".txz") -> ".txz" to ArchiveFormat.TAR
            lowerPath.endsWith(".tar.zst") -> ".tar.zst" to ArchiveFormat.TAR
            lowerPath.endsWith(".tar.gz") -> ".tar.gz" to ArchiveFormat.TAR
            lowerPath.endsWith(".tgz") -> ".tgz" to ArchiveFormat.TAR
            lowerPath.endsWith(".tar") -> ".tar" to ArchiveFormat.TAR
            lowerPath.endsWith(".zip") -> ".zip" to ArchiveFormat.ZIP
            else -> ".pkg" to null
        }

        val abiSuffix = abi
            ?.trim()
            ?.lowercase()
            ?.replace(Regex("[^a-z0-9._-]"), "-")
            ?.takeIf { it.isNotBlank() }
            ?.let { "-$it" }
            .orEmpty()
        return ArchiveTarget(
            file = File(
                downloadDir,
                "$packageId-${sanitizeFileComponent(version)}$abiSuffix-$operationId${suffixAndFormat.first}",
            ),
            formatHint = suffixAndFormat.second
        )
    }

    private fun sanitizeFileComponent(value: String): String = value
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .take(128)
        .ifBlank { "unknown" }

    private fun detectArchiveFormat(file: File, formatHint: ArchiveFormat?): ArchiveFormat? {
        val detected = detectArchiveFormatByMagic(file) ?: return null
        return detected.takeIf { formatHint == null || formatHint == detected }
    }

    private fun detectArchiveFormatByMagic(file: File): ArchiveFormat? {
        val header = ByteArray(512)
        val bytesRead = FileInputStream(file).use { input ->
            input.read(header)
        }

        if (bytesRead >= 4 &&
            header[0] == 0x50.toByte() &&
            header[1] == 0x4B.toByte() &&
            (header[2] == 0x03.toByte() || header[2] == 0x05.toByte() || header[2] == 0x07.toByte()) &&
            (header[3] == 0x04.toByte() || header[3] == 0x06.toByte() || header[3] == 0x08.toByte())
        ) {
            return ArchiveFormat.ZIP
        }

        if (bytesRead >= 2 &&
            header[0] == 0x1F.toByte() &&
            header[1] == 0x8B.toByte()
        ) {
            return ArchiveFormat.TAR
        }

        if (bytesRead >= 4 &&
            header[0] == 0x28.toByte() &&
            header[1] == 0xB5.toByte() &&
            header[2] == 0x2F.toByte() &&
            header[3] == 0xFD.toByte()
        ) {
            return ArchiveFormat.TAR
        }

        if (bytesRead >= 6 &&
            header[0] == 0xFD.toByte() &&
            header[1] == 0x37.toByte() &&
            header[2] == 0x7A.toByte() &&
            header[3] == 0x58.toByte() &&
            header[4] == 0x5A.toByte() &&
            header[5] == 0x00.toByte()
        ) {
            return ArchiveFormat.TAR
        }

        if (bytesRead >= 262) {
            val tarMagic = byteArrayOf(
                'u'.code.toByte(),
                's'.code.toByte(),
                't'.code.toByte(),
                'a'.code.toByte(),
                'r'.code.toByte()
            )
            val matchesTar = tarMagic.indices.all { header[257 + it] == tarMagic[it] }
            if (matchesTar) {
                return ArchiveFormat.TAR
            }
        }

        return null
    }

    suspend fun uninstall(packageId: String): UninstallResult {
        return try {
            RegistryPackageId.requireValid(packageId)
            PackageInstallCoordinator.withExclusiveAccess(installDir) {
                cleanupInterruptedInstallDirectories()
                val targetDir = resolvePackageDirectory(packageId)
                if (targetDir.exists()) {
                    val freedSpace = targetDir.walkTopDown().sumOf { it.length() }
                    check(targetDir.deleteRecursively()) { "Failed to delete installed package directory" }
                    Timber.tag(TAG).d("Uninstalled $packageId, freed $freedSpace bytes")
                    UninstallResult.Success(packageId, Platform.ANDROID, freedSpace)
                } else {
                    UninstallResult.Failure(packageId, UninstallError.PackageNotFound(packageId))
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Uninstall failed")
            UninstallResult.Failure(packageId, UninstallError.UnknownError(e.message ?: "Unknown error"))
        }
    }

    fun isInstalled(packageId: String): Boolean {
        val targetDir = resolvePackageDirectory(packageId)
        return targetDir.exists() && targetDir.listFiles()?.isNotEmpty() == true
    }

    fun getInstallPath(packageId: String): File = resolvePackageDirectory(packageId)

    suspend fun clearDownloadCache() = downloadCacheMutex.withLock {
        downloader.clearTempFiles()
        downloadDir.listFiles()?.forEach { it.delete() }
    }

    private fun resolvePackageDirectory(packageId: String): File {
        RegistryPackageId.requireValid(packageId)
        val root = installDir.canonicalFile
        val candidate = File(root, packageId).canonicalFile
        require(candidate.parentFile == root) { "Package path escapes install directory" }
        return candidate
    }

    private fun requireValidPackageMetadata(
        packageDir: File,
        expectedPackageId: String,
        expectedVersion: String,
    ): InstalledPackageMetadata {
        val metadataFile = File(packageDir, "package.json")
        require(metadataFile.isFile) { "Downloaded package is missing package.json" }
        val metadata = JsonSerializer.decodeFromFile<InstalledPackageMetadata>(metadataFile)
        require(metadata.id == expectedPackageId) { "Downloaded package id does not match the request" }
        require(metadata.version == expectedVersion) { "Downloaded package version does not match the request" }
        require(metadata.name.isNotBlank()) { "Downloaded package name is empty" }
        return metadata
    }

    private fun replaceDirectoryAtomically(
        stagingDir: File,
        targetDir: File,
        packageId: String,
        operationId: String,
    ) {
        val staleBackup = PackageInstallCoordinator.publishStagedDirectory(
            installDir = installDir,
            stagingDir = stagingDir,
            targetDir = targetDir,
            backupPrefix = PackageInstallCoordinator.DOWNLOAD_BACKUP_PREFIX,
            packageId = packageId,
            operationId = operationId,
        )
        if (staleBackup != null) {
            Timber.tag(TAG).w("Failed to delete package backup: ${staleBackup.absolutePath}")
        }
    }

    private fun cleanupInterruptedInstallDirectories() {
        installDir.listFiles().orEmpty().filter(File::isDirectory).forEach { staleDir ->
            when {
                staleDir.name.startsWith(PackageInstallCoordinator.DOWNLOAD_STAGING_PREFIX) ->
                    deleteStaleDirectory(staleDir)
                staleDir.name.startsWith(PackageInstallCoordinator.DOWNLOAD_BACKUP_PREFIX) ->
                    recoverOrDeleteBackup(staleDir)
            }
        }
    }

    private fun recoverOrDeleteBackup(backupDir: File) {
        val packageId = PackageInstallCoordinator.packageIdFromTransactionDirectory(
            backupDir,
            PackageInstallCoordinator.DOWNLOAD_BACKUP_PREFIX,
        )
        val targetDir = packageId?.let(::resolvePackageDirectory)
        val metadata = runCatching {
            val metadataFile = File(backupDir, "package.json")
            metadataFile.takeIf(File::isFile)
                ?.let { JsonSerializer.decodeFromFile<InstalledPackageMetadata>(it) }
        }.getOrNull()
        if (targetDir != null && !targetDir.exists() && metadata?.id == packageId) {
            if (backupDir.renameTo(targetDir)) {
                Timber.tag(TAG).w("Recovered interrupted package install: $packageId")
                return
            }
            Timber.tag(TAG).e("Failed to recover package backup: ${backupDir.absolutePath}")
            return
        }
        deleteStaleDirectory(backupDir)
    }

    private fun deleteStaleDirectory(staleDir: File) {
        if (!staleDir.deleteRecursively()) {
            Timber.tag(TAG).w("Failed to clean stale package directory: ${staleDir.absolutePath}")
        }
    }
}
