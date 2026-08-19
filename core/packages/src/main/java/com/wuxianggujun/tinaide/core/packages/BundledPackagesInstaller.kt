package com.wuxianggujun.tinaide.core.packages

import android.content.Context
import com.wuxianggujun.tinaide.core.common.io.TarExtractor
import com.wuxianggujun.tinaide.core.common.registry.RegistryPackageId
import com.wuxianggujun.tinaide.core.packages.model.InstallType
import com.wuxianggujun.tinaide.core.packages.model.Platform
import com.wuxianggujun.tinaide.core.packages.store.LocalInstallStateStore
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import timber.log.Timber

/**
 * 内置包安装器
 *
 * 负责从 assets/bundled_packages/ 解压预编译的库到 filesDir/installed-packages/
 * 支持多种压缩格式：.tar.xz, .tar.zst, .tar.gz, .zip
 * 并自动解析 package.json 元数据，更新安装状态
 */
class BundledPackagesInstaller(
    private val context: Context,
    private val installStateStore: LocalInstallStateStore
) {
    companion object {
        private const val TAG = "BundledPackagesInstaller"
        private const val ASSET_DIR = "bundled_packages"
        private const val INSTALL_DIR_NAME = "installed-packages"
    }

    private val installDir: File by lazy {
        File(context.filesDir, INSTALL_DIR_NAME).also { it.mkdirs() }
    }

    /**
     * 安装所有内置包
     *
     * 扫描 assets/bundled_packages/ 目录，解压所有支持的压缩包
     * 支持格式: .tar.xz, .tar.zst, .tar.gz, .zip
     *
     * @param forceReinstall 是否强制重新安装（删除已有目录）
     */
    suspend fun installBundledPackages(forceReinstall: Boolean = false) = withContext(Dispatchers.IO) {
        PackageInstallCoordinator.withExclusiveAccess(installDir) {
            installBundledPackagesLocked(forceReinstall)
        }
    }

    private fun installBundledPackagesLocked(forceReinstall: Boolean) {
        cleanupInterruptedInstallDirectories()
        val assetManager = context.assets
        val entries = runCatching {
            assetManager.list(ASSET_DIR).orEmpty().toList()
        }.getOrDefault(emptyList())

        if (entries.isEmpty()) {
            Timber.tag(TAG).d("No bundled packages found in assets")
            return
        }

        Timber.tag(TAG).i("Found ${entries.size} bundled package(s)")

        for (entry in entries) {
            val packageInfo = parsePackageFileName(entry) ?: continue
            val assetPath = "$ASSET_DIR/$entry"

            try {
                installPackageFromAsset(packageInfo.id, assetPath, packageInfo.format, forceReinstall)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to install bundled package: ${packageInfo.id}")
            }
        }
    }

    /**
     * 解析包文件名，提取包 ID 和压缩格式
     */
    private fun parsePackageFileName(fileName: String): PackageFileInfo? = when {
        fileName.endsWith(".tar.xz", ignoreCase = true) ->
            PackageFileInfo(fileName.dropLast(".tar.xz".length), CompressionFormat.TAR_XZ)
        fileName.endsWith(".tar.zst", ignoreCase = true) ->
            PackageFileInfo(fileName.dropLast(".tar.zst".length), CompressionFormat.TAR_ZSTD)
        fileName.endsWith(".tar.gz", ignoreCase = true) ->
            PackageFileInfo(fileName.dropLast(".tar.gz".length), CompressionFormat.TAR_GZ)
        fileName.endsWith(".zip", ignoreCase = true) ->
            PackageFileInfo(fileName.dropLast(".zip".length), CompressionFormat.ZIP)
        else -> null
    }

    private data class PackageFileInfo(
        val id: String,
        val format: CompressionFormat
    )

    private enum class CompressionFormat {
        TAR_XZ,
        TAR_ZSTD,
        TAR_GZ,
        ZIP;

        fun toTarExtractorType(): TarExtractor.CompressionType? = when (this) {
            TAR_XZ -> TarExtractor.CompressionType.XZ
            TAR_ZSTD -> TarExtractor.CompressionType.ZSTD
            TAR_GZ -> TarExtractor.CompressionType.GZIP
            ZIP -> null // ZIP 单独处理
        }
    }

    /**
     * 从 assets 安装单个包
     */
    private fun installPackageFromAsset(
        packageId: String,
        assetPath: String,
        format: CompressionFormat,
        forceReinstall: Boolean = false
    ) {
        RegistryPackageId.requireValid(packageId)
        val targetDir = resolvePackageDirectory(packageId)
        val installedMetadata = readValidPackageMetadata(targetDir)
            ?.takeIf { metadata -> metadata.id == packageId }
        if (!forceReinstall && installedMetadata != null) {
            recordInstalledPackage(installedMetadata, targetDir)
            Timber.tag(TAG).d("Package $packageId already installed and valid, skipping")
            return
        }

        if (targetDir.exists()) {
            Timber.tag(TAG).w("Package $packageId is incomplete or force reinstall was requested; replacing atomically")
        }

        Timber.tag(TAG).i("Installing bundled package: $packageId from $assetPath (format: $format)")

        val operationId = PackageInstallCoordinator.createOperationId()
        val tempFile = File(context.cacheDir, "bundled_${packageId}_$operationId.tmp")
        val stagingDir = PackageInstallCoordinator.resolveTransactionDirectory(
            installDir,
            PackageInstallCoordinator.BUNDLED_STAGING_PREFIX,
            packageId,
            operationId,
        )
        try {
            context.assets.open(assetPath).use { input ->
                tempFile.outputStream().use { output ->
                    copyArchiveWithLimit(input, output)
                }
            }

            // 根据格式解压
            check(stagingDir.mkdirs()) { "Failed to create staging directory: ${stagingDir.absolutePath}" }
            when (format) {
                CompressionFormat.ZIP -> PackageArchivePolicy.extractZip(tempFile, stagingDir)
                else -> {
                    // 使用 TarExtractor 统一处理 tar.xz/tar.zst/tar.gz
                    val compressionType = format.toTarExtractorType()
                        ?: throw IllegalStateException("Unsupported format: $format")
                    tempFile.inputStream().use { input ->
                        TarExtractor.extract(
                            input = input,
                            targetDir = stagingDir,
                            compressionType = compressionType,
                            limits = PackageArchivePolicy.limits,
                        )
                    }
                }
            }

            val metadata = checkNotNull(readValidPackageMetadata(stagingDir)) {
                "Bundled package $packageId is missing a valid package.json"
            }
            check(metadata.id == packageId) {
                "Bundled package metadata id ${metadata.id} does not match archive id $packageId"
            }
            replaceDirectoryAtomically(stagingDir, targetDir, packageId, operationId)
            recordInstalledPackage(metadata, targetDir)
            Timber.tag(TAG).i("✓ Bundled package installed: ${metadata.name} v${metadata.version}")
        } finally {
            tempFile.delete()
            if (stagingDir.exists()) stagingDir.deleteRecursively()
        }
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
            backupPrefix = PackageInstallCoordinator.BUNDLED_BACKUP_PREFIX,
            packageId = packageId,
            operationId = operationId,
        )
        if (staleBackup != null) {
            Timber.tag(TAG).w("Failed to delete bundled package backup: ${staleBackup.absolutePath}")
        }
    }

    private fun cleanupInterruptedInstallDirectories() {
        installDir.listFiles().orEmpty().filter(File::isDirectory).forEach { staleDir ->
            when {
                staleDir.name.startsWith(PackageInstallCoordinator.BUNDLED_STAGING_PREFIX) ->
                    deleteStaleDirectory(staleDir)
                staleDir.name.startsWith(PackageInstallCoordinator.BUNDLED_BACKUP_PREFIX) ->
                    recoverOrDeleteBackup(staleDir, PackageInstallCoordinator.BUNDLED_BACKUP_PREFIX)
                staleDir.name.startsWith(PackageInstallCoordinator.DOWNLOAD_STAGING_PREFIX) ->
                    deleteStaleDirectory(staleDir)
                staleDir.name.startsWith(PackageInstallCoordinator.DOWNLOAD_BACKUP_PREFIX) ->
                    recoverOrDeleteBackup(staleDir, PackageInstallCoordinator.DOWNLOAD_BACKUP_PREFIX)
            }
        }
    }

    private fun recoverOrDeleteBackup(backupDir: File, prefix: String) {
        val packageId = PackageInstallCoordinator.packageIdFromTransactionDirectory(backupDir, prefix)
        val targetDir = packageId?.let(::resolvePackageDirectory)
        val metadata = readValidPackageMetadata(backupDir)
        if (targetDir != null && !targetDir.exists() && metadata?.id == packageId) {
            if (backupDir.renameTo(targetDir)) {
                Timber.tag(TAG).w("Recovered interrupted bundled package install: $packageId")
                return
            }
            // 旧包仍是此时唯一的有效副本；恢复失败时保留备份，下一次启动继续重试。
            Timber.tag(TAG).e("Failed to recover bundled package backup: ${backupDir.absolutePath}")
            return
        }
        deleteStaleDirectory(backupDir)
    }

    private fun deleteStaleDirectory(staleDir: File) {
        if (!staleDir.deleteRecursively()) {
            Timber.tag(TAG).w("Failed to clean stale bundled package directory: ${staleDir.absolutePath}")
        }
    }

    private fun readValidPackageMetadata(packageDir: File): PackageMetadata? {
        if (!packageDir.isDirectory || packageDir.listFiles().isNullOrEmpty()) return null
        return readPackageMetadata(packageDir)?.takeIf { metadata ->
            RegistryPackageId.isValid(metadata.id) && metadata.name.isNotBlank() && metadata.version.isNotBlank()
        }
    }

    private fun recordInstalledPackage(metadata: PackageMetadata, packageDir: File) {
        installStateStore.setInstalled(
            packageId = metadata.id,
            platform = Platform.ANDROID,
            version = metadata.version,
            packageName = metadata.name,
            installType = InstallType.DOWNLOAD,
            size = calculatePackageSize(packageDir),
            isBundled = true,
        )
    }

    /**
     * 读取包元数据
     */
    private fun readPackageMetadata(packageDir: File): PackageMetadata? {
        val metadataFile = File(packageDir, "package.json")
        if (!metadataFile.exists()) return null

        return try {
            JsonSerializer.decodeFromFile<PackageMetadata>(metadataFile)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to parse package.json")
            null
        }
    }

    /**
     * 计算包大小
     */
    private fun calculatePackageSize(dir: File): Long = dir.walkTopDown()
        .filter { it.isFile }
        .map { it.length() }
        .sum()

    private fun resolvePackageDirectory(packageId: String): File {
        RegistryPackageId.requireValid(packageId)
        val root = installDir.canonicalFile
        val candidate = File(root, packageId).canonicalFile
        require(candidate.parentFile == root) { "Package path escapes install directory" }
        return candidate
    }

    private fun copyArchiveWithLimit(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copiedBytes = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            copiedBytes += count.toLong()
            check(copiedBytes <= PackageArchivePolicy.limits.maxArchiveBytes) {
                "Bundled package archive is larger than the allowed size"
            }
            output.write(buffer, 0, count)
        }
    }

    /**
     * 包元数据（对应 package.json）
     */
    @Serializable
    data class PackageMetadata(
        val id: String,
        val name: String,
        val version: String,
        val packageRevision: Int? = null,
        val upstreamName: String? = null,
        val upstreamVersion: String? = null,
        val upstreamTag: String? = null,
        val upstreamCommit: String? = null,
        val description: String? = null,
        val platform: String? = null,
        val artifactType: String? = null,
        val installType: String? = null,
        val category: String? = null,
        val homepage: String? = null,
        val license: String? = null,
        val installedAt: Long? = null,
        val files: PackageFiles? = null,
        val abis: List<String>? = null
    )

    @Serializable
    data class PackageFiles(
        val include: String? = null,
        val source: String? = null,
        val lib: String? = null,
        val pkgconfig: String? = null
    )
}
