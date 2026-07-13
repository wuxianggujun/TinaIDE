package com.wuxianggujun.tinaide.core.packages

import android.content.Context
import com.wuxianggujun.tinaide.core.common.io.ArchivePathSafety
import com.wuxianggujun.tinaide.core.common.io.TarExtractor
import com.wuxianggujun.tinaide.core.packages.model.InstallType
import com.wuxianggujun.tinaide.core.packages.model.Platform
import com.wuxianggujun.tinaide.core.packages.store.LocalInstallStateStore
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        private const val INSTALL_LOCK_FILE_NAME = ".bundled-install.lock"
        private const val STAGING_PREFIX = ".bundled-staging-"
        private const val BACKUP_PREFIX = ".bundled-backup-"
        private const val UUID_TEXT_LENGTH = 36

        private val installMutex = Mutex()
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
        installMutex.withLock {
            val lockFile = File(installDir, INSTALL_LOCK_FILE_NAME)
            RandomAccessFile(lockFile, "rw").channel.use { channel ->
                val fileLock = channel.lock()
                try {
                    installBundledPackagesLocked(forceReinstall)
                } finally {
                    fileLock.release()
                }
            }
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
            PackageFileInfo(fileName.removeSuffix(".tar.xz"), CompressionFormat.TAR_XZ)
        fileName.endsWith(".tar.zst", ignoreCase = true) ->
            PackageFileInfo(fileName.removeSuffix(".tar.zst"), CompressionFormat.TAR_ZSTD)
        fileName.endsWith(".tar.gz", ignoreCase = true) ->
            PackageFileInfo(fileName.removeSuffix(".tar.gz"), CompressionFormat.TAR_GZ)
        fileName.endsWith(".zip", ignoreCase = true) ->
            PackageFileInfo(fileName.removeSuffix(".zip"), CompressionFormat.ZIP)
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
        val targetDir = File(installDir, packageId)
        val installedMetadata = readValidPackageMetadata(targetDir)
        if (!forceReinstall && installedMetadata != null) {
            recordInstalledPackage(installedMetadata, targetDir)
            Timber.tag(TAG).d("Package $packageId already installed and valid, skipping")
            return
        }

        if (targetDir.exists()) {
            Timber.tag(TAG).w("Package $packageId is incomplete or force reinstall was requested; replacing atomically")
        }

        Timber.tag(TAG).i("Installing bundled package: $packageId from $assetPath (format: $format)")

        val operationId = UUID.randomUUID().toString()
        val tempFile = File(context.cacheDir, "bundled_${packageId}_$operationId.tmp")
        val stagingDir = File(installDir, "$STAGING_PREFIX$packageId-$operationId")
        try {
            context.assets.open(assetPath).use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            // 根据格式解压
            check(stagingDir.mkdirs()) { "Failed to create staging directory: ${stagingDir.absolutePath}" }
            when (format) {
                CompressionFormat.ZIP -> extractZip(tempFile, stagingDir)
                else -> {
                    // 使用 TarExtractor 统一处理 tar.xz/tar.zst/tar.gz
                    val compressionType = format.toTarExtractorType()
                        ?: throw IllegalStateException("Unsupported format: $format")
                    tempFile.inputStream().use { input ->
                        TarExtractor.extract(input, stagingDir, compressionType)
                    }
                }
            }

            val metadata = checkNotNull(readValidPackageMetadata(stagingDir)) {
                "Bundled package $packageId is missing a valid package.json"
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
        val backupDir = File(installDir, "$BACKUP_PREFIX$packageId-$operationId")
        var oldTargetMoved = false
        try {
            if (targetDir.exists()) {
                check(targetDir.renameTo(backupDir)) {
                    "Failed to move existing package to backup: ${targetDir.absolutePath}"
                }
                oldTargetMoved = true
            }
            check(stagingDir.renameTo(targetDir)) {
                "Failed to publish bundled package: ${targetDir.absolutePath}"
            }
            if (oldTargetMoved && !backupDir.deleteRecursively()) {
                Timber.tag(TAG).w("Failed to delete bundled package backup: ${backupDir.absolutePath}")
            }
        } catch (error: Throwable) {
            if (oldTargetMoved && !targetDir.exists()) {
                runCatching { backupDir.renameTo(targetDir) }
                    .onFailure { restoreError -> error.addSuppressed(restoreError) }
            }
            throw error
        }
    }

    private fun cleanupInterruptedInstallDirectories() {
        installDir.listFiles().orEmpty().filter(File::isDirectory).forEach { staleDir ->
            when {
                staleDir.name.startsWith(STAGING_PREFIX) -> deleteStaleDirectory(staleDir)
                staleDir.name.startsWith(BACKUP_PREFIX) -> recoverOrDeleteBackup(staleDir)
            }
        }
    }

    private fun recoverOrDeleteBackup(backupDir: File) {
        val backupSuffix = backupDir.name.removePrefix(BACKUP_PREFIX)
        val packageId = backupSuffix
            .takeIf { it.length > UUID_TEXT_LENGTH + 1 }
            ?.dropLast(UUID_TEXT_LENGTH + 1)
            ?.takeIf(String::isNotBlank)
        val targetDir = packageId?.let { File(installDir, it) }
        if (targetDir != null && !targetDir.exists() && readValidPackageMetadata(backupDir) != null) {
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
            metadata.id.isNotBlank() && metadata.name.isNotBlank() && metadata.version.isNotBlank()
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
     * 解压 ZIP 格式
     */
    private fun extractZip(archiveFile: File, targetDir: File) {
        ZipFile(archiveFile).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val entryFile = ArchivePathSafety.resolveEntryFile(targetDir, entry.name, "zip entry")
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
            }
        }
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
