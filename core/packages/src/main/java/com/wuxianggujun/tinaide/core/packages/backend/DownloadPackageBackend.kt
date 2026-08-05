package com.wuxianggujun.tinaide.core.packages.backend

import android.content.Context
import android.os.Build
import com.wuxianggujun.tinaide.core.common.io.TarExtractor
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str
import com.wuxianggujun.tinaide.core.network.ApiResult
import com.wuxianggujun.tinaide.core.network.registry.GitHubRegistryHttpClientFactory
import com.wuxianggujun.tinaide.core.packages.PackageAbiCompatibility
import com.wuxianggujun.tinaide.core.packages.PackageDownloadSourceSelector
import com.wuxianggujun.tinaide.core.packages.api.PackageApiClient
import com.wuxianggujun.tinaide.core.packages.download.DownloadError
import com.wuxianggujun.tinaide.core.packages.download.DownloadResult
import com.wuxianggujun.tinaide.core.packages.download.ResumableDownloader
import com.wuxianggujun.tinaide.core.packages.model.*
import java.io.File
import java.io.FileInputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile
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

    suspend fun install(
        packageId: String,
        versionId: Int,
        version: String,
        progress: (InstallProgressEvent) -> Unit
    ): InstallResult {
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
        val targetAbi = PackageAbiCompatibility.currentAppAbi(
            nativeLibraryDir = context.applicationInfo.nativeLibraryDir,
            supportedAbis = Build.SUPPORTED_ABIS,
        )
        val sortedSources = PackageDownloadSourceSelector.select(downloadInfo.sources, targetAbi)
        if (sortedSources.isEmpty()) {
            val error = InstallError.UnknownError(Strings.pkg_manager_error_no_download_source_for_abi.str(targetAbi))
            progress(InstallProgressEvent.Failed(error))
            return InstallResult.Failure(packageId, error)
        }

        var lastError: DownloadError? = null

        for (source in sortedSources) {
            val archiveTarget = buildArchiveTarget(packageId, version, source.abi, source.url)
            Timber.tag(TAG).d("Trying source: ${source.name}, abi=${source.abi ?: "legacy"} (${source.url})")
            progress(InstallProgressEvent.Preparing("Downloading from ${source.name}..."))

            val downloadResult = downloader.download(
                url = source.url,
                targetFile = archiveTarget.file,
                checksum = source.checksum ?: downloadInfo.checksum,
                supportsRange = source.supportsRange
            ) { downloaded, total, speed ->
                progress(InstallProgressEvent.Downloading(downloaded, total, speed))
            }

            when (downloadResult) {
                is DownloadResult.Success -> {
                    val archiveFormat = detectArchiveFormat(downloadResult.file, archiveTarget.formatHint)
                    if (archiveFormat == null) {
                        lastError = DownloadError.IOError("Unsupported archive format: ${source.url}")
                        Timber.tag(TAG).w("Unsupported archive file: ${downloadResult.file.name}")
                        downloadResult.file.delete()
                        continue
                    }

                    progress(InstallProgressEvent.Verifying("Download complete, extracting..."))

                    val extractResult = extractPackage(
                        archiveFile = downloadResult.file,
                        archiveFormat = archiveFormat,
                        extractPath = packageId,
                        progress = progress
                    )

                    if (extractResult.isSuccess) {
                        downloadResult.file.delete()
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
            is DownloadError.ChecksumMismatch -> InstallError.ChecksumMismatch(lastError.expected, lastError.actual)
            is DownloadError.IOError -> InstallError.NetworkError(lastError.message)
            null -> InstallError.UnknownError("All download sources failed")
        }
        progress(InstallProgressEvent.Failed(error))
        return InstallResult.Failure(packageId, error)
    }

    private fun extractPackage(
        archiveFile: File,
        archiveFormat: ArchiveFormat,
        extractPath: String,
        progress: (InstallProgressEvent) -> Unit
    ): Result<Unit> = try {
        val targetDir = File(installDir, extractPath)
        if (targetDir.exists()) {
            targetDir.deleteRecursively()
        }
        targetDir.mkdirs()

        when (archiveFormat) {
            ArchiveFormat.ZIP -> extractZip(archiveFile, targetDir, progress)
            ArchiveFormat.TAR -> {
                TarExtractor.extract(archiveFile, targetDir) { pct ->
                    progress(InstallProgressEvent.Extracting(pct))
                }
            }
        }

        Timber.tag(TAG).d("Extracted to ${targetDir.absolutePath}")
        Result.success(Unit)
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "Extraction failed")
        File(installDir, extractPath).deleteRecursively()
        Result.failure(e)
    }

    private fun extractZip(
        archiveFile: File,
        targetDir: File,
        progress: (InstallProgressEvent) -> Unit
    ) {
        ZipFile(archiveFile).use { zip ->
            val entries = zip.entries().toList()
            val total = entries.size.coerceAtLeast(1)
            var extracted = 0

            for (entry in entries) {
                val safeName = sanitizeArchivePath(entry.name)
                val entryFile = File(targetDir, safeName)

                if (entry.isDirectory) {
                    entryFile.mkdirs()
                } else {
                    entryFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        entryFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }

                extracted++
                if (extracted % 10 == 0 || extracted == total) {
                    val pct = extracted.toFloat() / total
                    progress(InstallProgressEvent.Extracting(pct))
                }
            }
        }
    }

    private fun buildArchiveTarget(packageId: String, version: String, abi: String?, url: String): ArchiveTarget {
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
            file = File(downloadDir, "$packageId-$version$abiSuffix${suffixAndFormat.first}"),
            formatHint = suffixAndFormat.second
        )
    }

    private fun sanitizeArchivePath(path: String): String {
        var normalized = path.replace('\\', '/')
        while (normalized.startsWith("./")) normalized = normalized.removePrefix("./")
        while (normalized.startsWith("/")) normalized = normalized.removePrefix("/")

        if (normalized == ".." || normalized.startsWith("../") || normalized.contains("/../")) {
            throw IllegalArgumentException("Invalid archive path: $path")
        }
        return normalized
    }

    private fun detectArchiveFormat(file: File, formatHint: ArchiveFormat?): ArchiveFormat? {
        if (formatHint != null) return formatHint

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
        val targetDir = File(installDir, packageId)
        return try {
            if (targetDir.exists()) {
                val freedSpace = targetDir.walkTopDown().sumOf { it.length() }
                targetDir.deleteRecursively()
                Timber.tag(TAG).d("Uninstalled $packageId, freed $freedSpace bytes")
                UninstallResult.Success(packageId, Platform.ANDROID, freedSpace)
            } else {
                UninstallResult.Failure(packageId, UninstallError.PackageNotFound(packageId))
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Uninstall failed")
            UninstallResult.Failure(packageId, UninstallError.UnknownError(e.message ?: "Unknown error"))
        }
    }

    fun isInstalled(packageId: String): Boolean {
        val targetDir = File(installDir, packageId)
        return targetDir.exists() && targetDir.listFiles()?.isNotEmpty() == true
    }

    fun getInstallPath(packageId: String): File = File(installDir, packageId)

    fun clearDownloadCache() {
        downloader.clearTempFiles()
        downloadDir.listFiles()?.forEach { it.delete() }
    }
}
