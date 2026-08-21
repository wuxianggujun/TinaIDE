package com.wuxianggujun.tinaide.plugin.lsp

import android.content.Context
import com.wuxianggujun.tinaide.core.common.io.ArchiveExtractionLimits
import com.wuxianggujun.tinaide.core.common.io.TarExtractor
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.network.executeCancellable
import com.wuxianggujun.tinaide.core.linux.LinuxEnvironment
import com.wuxianggujun.tinaide.core.linux.LinuxEnvironmentProvider
import com.wuxianggujun.tinaide.core.linux.UnavailableLinuxEnvironmentProvider
import com.wuxianggujun.tinaide.core.proot.GuestSystemPackageManager
import com.wuxianggujun.tinaide.core.proot.LinuxDistroRootfsHealthProbe
import com.wuxianggujun.tinaide.core.proot.LinuxDistroRootfsHealthReport
import com.wuxianggujun.tinaide.core.proot.PRootBootstrap
import com.wuxianggujun.tinaide.core.proot.PRootEnvironment
import com.wuxianggujun.tinaide.core.proot.RootfsPackageManager
import com.wuxianggujun.tinaide.core.proot.displayName
import com.wuxianggujun.tinaide.core.proot.resolveGuestPackageManager
import com.wuxianggujun.tinaide.core.proot.toHealthSummary
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/**
 * LSP 工具链安装器
 *
 * 支持多种安装方式：
 * - system: 通过当前 Linux 发行版包管理器安装系统包
 * - download: 下载并解压二进制文件
 * - pip: 通过 pip 安装 Python 包
 * - npm: 通过 npm 安装 Node.js 包
 */
class LspToolchainInstaller(
    private val context: Context,
    private val linuxEnvironmentProvider: LinuxEnvironmentProvider = UnavailableLinuxEnvironmentProvider,
    private val packageManagerResolver: (LinuxEnvironment) -> RootfsPackageManager = { environment ->
        environment.resolveGuestPackageManager()
    },
    private val linuxHealthReportProvider: suspend (LinuxEnvironment) -> LinuxDistroRootfsHealthReport? = { environment ->
        (environment as? PRootEnvironment)?.checkLinuxDistroHealth()
    },
) {
    companion object {
        private const val TAG = "LspToolchainInstaller"
        private const val PACKAGE_UPDATE_TIMEOUT_MS = 120_000L
        private const val PACKAGE_INSTALL_TIMEOUT_MS = 600_000L
        private const val PIP_INSTALL_TIMEOUT_MS = 300_000L
        private const val DOWNLOAD_TIMEOUT_MS = 600_000L
        private const val VERIFY_TIMEOUT_MS = 10_000L
        private const val MAX_VERIFY_OUTPUT_CHARS = 64 * 1024
        private const val MAX_DOWNLOAD_BYTES = 512L * 1024L * 1024L
        private val DOWNLOAD_EXTRACTION_LIMITS = ArchiveExtractionLimits(
            maxArchiveBytes = MAX_DOWNLOAD_BYTES,
            maxExpandedBytes = 2L * 1024L * 1024L * 1024L,
            maxEntryBytes = 512L * 1024L * 1024L,
            maxEntryCount = 50_000,
            maxCompressionRatio = 500L,
        )
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 安装工具链
     */
    suspend fun install(
        config: LspToolchainConfig,
        progress: (LspInstallProgress) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        Timber.tag(TAG).i("Installing toolchain: ${config.id} (${config.type})")

        val linuxEnvironment = linuxEnvironmentProvider.get()
        if (!linuxEnvironment.isAvailable()) {
            return@withContext Result.failure(IllegalStateException(Strings.lsp_toolchain_error_linux_unavailable.strOr(context)))
        }
        validateLinuxDistroHealth(linuxEnvironment)?.let { failure ->
            return@withContext failure
        }

        try {
            when (config.normalizedType()) {
                "system" -> installSystemPackages(config, progress)
                "download" -> installDownload(config, progress)
                "pip" -> installPip(config, progress)
                "npm" -> installNpm(config, progress)
                else -> Result.failure(IllegalArgumentException(Strings.lsp_toolchain_error_unknown_type.strOr(context, config.type)))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to install toolchain: ${config.id}")
            Result.failure(e)
        }
    }

    /**
     * 安装前复用自研 Linux rootfs 健康检查，避免在损坏环境中继续写入工具链。
     */
    private suspend fun validateLinuxDistroHealth(
        linuxEnvironment: LinuxEnvironment,
    ): Result<Unit>? {
        val report = try {
            linuxHealthReportProvider(linuxEnvironment)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return Result.failure(
                IllegalStateException(
                    Strings.lsp_toolchain_error_linux_health_check_failed.strOr(
                        context,
                        error.message ?: Strings.error_unknown.strOr(context),
                    ),
                    error,
                )
            )
        } ?: return null

        if (report.isUsable) return null

        val summary = report.toHealthSummary { probe -> probe.toDisplayName() }
        val missingItems = summary.requiredMissingItems
            .ifEmpty { report.requiredFailures.map { check -> check.probe.toDisplayName() } }
            .distinct()
            .joinToString(", ")
            .ifBlank { Strings.error_unknown.strOr(context) }

        return Result.failure(
            IllegalStateException(
                Strings.lsp_toolchain_error_linux_health_unusable.strOr(context, missingItems)
            )
        )
    }

    /**
     * 检查工具链是否已安装
     */
    suspend fun isInstalled(config: LspToolchainConfig): Boolean = withContext(Dispatchers.IO) {
        if (config.verifyCommand.isNullOrBlank()) return@withContext false
        val linuxEnvironment = linuxEnvironmentProvider.get()
        if (!linuxEnvironment.isAvailable()) return@withContext false

        try {
            val result = linuxEnvironment.execute(
                command = listOf("/bin/sh", "-c", config.verifyCommand),
                workDir = "/",
                timeout = VERIFY_TIMEOUT_MS
            )

            if (result.exitCode != 0) return@withContext false

            if (config.verifyPattern != null) {
                val pattern = Regex(config.verifyPattern)
                val output = (result.stdout + "\n" + result.stderr).take(MAX_VERIFY_OUTPUT_CHARS)
                return@withContext pattern.containsMatchIn(output)
            }

            true
        } catch (error: CancellationException) {
            throw error
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Verify command failed for ${config.id}")
            false
        }
    }

    private suspend fun installSystemPackages(
        config: LspToolchainConfig,
        progress: (LspInstallProgress) -> Unit
    ): Result<Unit> {
        val linuxEnvironment = linuxEnvironmentProvider.get()
        val packageManager = packageManagerResolver(linuxEnvironment)
        if (packageManager == RootfsPackageManager.UNKNOWN) {
            return Result.failure(IllegalStateException(Strings.lsp_toolchain_error_unknown_package_manager.strOr(context)))
        }

        val packages = config.resolvePackages(packageManager)
        if (packages.isEmpty()) {
            return Result.failure(
                IllegalArgumentException(
                    Strings.lsp_toolchain_error_no_system_packages.strOr(context, packageManager.displayName())
                )
            )
        }
        if (
            !LspToolchainPackagePolicy.areValid("system", packages) ||
            !LspToolchainPackagePolicy.areFallbackVersionsValid(config.fallbackVersions)
        ) {
            return Result.failure(
                IllegalArgumentException(Strings.lsp_toolchain_error_packages_invalid.strOr(context, config.id))
            )
        }

        progress(
            LspInstallProgress(
                phase = Strings.lsp_toolchain_phase_updating_package_index.strOr(
                    context,
                    packageManager.displayName(),
                ),
                progress = 0.1f,
                toolchainId = config.id
            )
        )

        val updateResult = GuestSystemPackageManager.updateIndex(
            linuxEnvironment = linuxEnvironment,
            packageManager = packageManager,
            timeoutMs = PACKAGE_UPDATE_TIMEOUT_MS,
        )

        if (updateResult.exitCode != 0) {
            Timber.tag(TAG).w("%s update failed: %s", packageManager, updateResult.stderr)
            // 继续尝试安装，可能缓存仍然可用
        }

        progress(
            LspInstallProgress(
                phase = Strings.lsp_toolchain_phase_installing_system_packages.strOr(
                    context,
                    packageManager.displayName(),
                ),
                progress = 0.3f,
                toolchainId = config.id,
                message = packages.joinToString(", ")
            )
        )

        val installResult = GuestSystemPackageManager.installPackages(
            linuxEnvironment = linuxEnvironment,
            packageManager = packageManager,
            packages = packages,
            timeoutMs = PACKAGE_INSTALL_TIMEOUT_MS,
        )

        if (installResult.exitCode != 0) {
            if (!config.fallbackVersions.isNullOrEmpty() && packageManager.supportsEqualsVersionFallback()) {
                for (fallback in config.fallbackVersions) {
                    Timber.tag(TAG).i("Trying fallback version: $fallback")
                    val fallbackPackages = packages.map { pkg ->
                        if (pkg.contains("=")) pkg else "$pkg=$fallback"
                    }
                    val fallbackResult = GuestSystemPackageManager.installPackages(
                        linuxEnvironment = linuxEnvironment,
                        packageManager = packageManager,
                        packages = fallbackPackages,
                        timeoutMs = PACKAGE_INSTALL_TIMEOUT_MS,
                    )
                    if (fallbackResult.exitCode == 0) {
                        return verifyAndReturn(config, progress)
                    }
                }
            }
            return Result.failure(
                RuntimeException(
                    Strings.lsp_toolchain_error_system_install_failed.strOr(
                        context,
                        packageManager.displayName(),
                        installResult.stderr.ifBlank { installResult.stdout },
                    )
                )
            )
        }

        return verifyAndReturn(config, progress)
    }

    private suspend fun installDownload(
        config: LspToolchainConfig,
        progress: (LspInstallProgress) -> Unit
    ): Result<Unit> {
        val url = config.url
            ?: return Result.failure(IllegalArgumentException(Strings.lsp_toolchain_error_no_download_url.strOr(context)))
        val extractTo = config.extractTo
            ?: return Result.failure(IllegalArgumentException(Strings.lsp_toolchain_error_no_extract_path.strOr(context)))
        val sha256 = config.sha256
            ?.takeIf { it.matches(Regex("(?i)^[0-9a-f]{64}$")) }
            ?: return Result.failure(
                IllegalArgumentException(Strings.lsp_toolchain_error_sha256_required.strOr(context))
            )
        if (!isHttpsUrl(url)) {
            return Result.failure(
                IllegalArgumentException(Strings.lsp_toolchain_error_https_required.strOr(context))
            )
        }

        progress(
            LspInstallProgress(
                phase = Strings.lsp_toolchain_phase_downloading.strOr(context),
                progress = 0.1f,
                toolchainId = config.id,
                message = runCatching { java.net.URI(url).host }.getOrNull()
            )
        )

        val rootfsDir = File(PRootBootstrap.getActiveRootfsPath(context)).canonicalFile
        val targetDir = File(rootfsDir, extractTo).canonicalFile
        if (!targetDir.toPath().startsWith(rootfsDir.toPath())) {
            return Result.failure(
                IllegalArgumentException(Strings.lsp_toolchain_error_extract_path_invalid.strOr(context))
            )
        }
        val safeId = config.id.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val operationId = UUID.randomUUID().toString()
        val tempFile = File(context.cacheDir, "lsp_download_${safeId}_$operationId.tar.gz")
        val transaction = LspDownloadInstallTransaction(
            targetDir = targetDir,
            toolchainId = config.id,
            operationId = operationId,
            unmanagedTargetMessage = Strings.lsp_toolchain_error_extract_path_occupied.strOr(context, extractTo),
        )

        try {
            // 下载文件
            downloadFile(url, tempFile) { downloaded, total ->
                val downloadProgress = if (total > 0) downloaded.toFloat() / total else 0f
                progress(
                    LspInstallProgress(
                        phase = Strings.lsp_toolchain_phase_downloading.strOr(context),
                        progress = 0.1f + 0.5f * downloadProgress,
                        toolchainId = config.id,
                        message = formatBytes(downloaded) + " / " + formatBytes(total)
                    )
                )
            }

            progress(
                LspInstallProgress(
                    phase = Strings.lsp_toolchain_phase_verifying_checksum.strOr(context),
                    progress = 0.65f,
                    toolchainId = config.id
                )
            )
            val actualSha256 = calculateSha256(tempFile)
            if (!actualSha256.equals(sha256, ignoreCase = true)) {
                return Result.failure(
                    RuntimeException(
                        Strings.lsp_toolchain_error_checksum_mismatch.strOr(context, sha256, actualSha256)
                    )
                )
            }

            progress(
                LspInstallProgress(
                    phase = Strings.lsp_toolchain_phase_extracting.strOr(context),
                    progress = 0.7f,
                    toolchainId = config.id
                )
            )

            val stagingDir = transaction.createStagingDirectory()
            TarExtractor.extract(tempFile, stagingDir, limits = DOWNLOAD_EXTRACTION_LIMITS)
            transaction.publish()

            val verifyResult = verifyAndReturn(config, progress)
            if (verifyResult.isSuccess) {
                if (!transaction.commit()) {
                    Timber.tag(TAG).w("Failed to delete previous toolchain backup for %s", config.id)
                }
                return verifyResult
            }

            transaction.rollback()
            return verifyResult
        } catch (error: Exception) {
            runCatching { transaction.rollback() }
                .onFailure(error::addSuppressed)
            throw error
        } finally {
            runCatching { transaction.cleanup() }
                .onFailure { error -> Timber.tag(TAG).w(error, "Failed to clean toolchain staging directory") }
            tempFile.delete()
        }
    }

    private suspend fun installPip(
        config: LspToolchainConfig,
        progress: (LspInstallProgress) -> Unit
    ): Result<Unit> {
        val packages = config.packages
        if (packages.isNullOrEmpty()) {
            return Result.failure(IllegalArgumentException(Strings.lsp_toolchain_error_no_pip_packages.strOr(context)))
        }
        if (!LspToolchainPackagePolicy.areValid("pip", packages)) {
            return Result.failure(
                IllegalArgumentException(Strings.lsp_toolchain_error_packages_invalid.strOr(context, config.id))
            )
        }

        progress(
            LspInstallProgress(
                phase = Strings.lsp_toolchain_phase_installing_python_packages.strOr(context),
                progress = 0.3f,
                toolchainId = config.id,
                message = packages.joinToString(", ")
            )
        )

        val linuxEnvironment = linuxEnvironmentProvider.get()
        val command = mutableListOf("pip3", "install", "--user", "--break-system-packages")
        command.addAll(packages)

        val result = linuxEnvironment.execute(
            command = command,
            workDir = "/",
            timeout = PIP_INSTALL_TIMEOUT_MS
        )

        if (result.exitCode != 0) {
            // 部分 pip 环境不支持 --break-system-packages。
            val fallbackCommand = mutableListOf("pip3", "install", "--user")
            fallbackCommand.addAll(packages)

            val fallbackResult = linuxEnvironment.execute(
                command = fallbackCommand,
                workDir = "/",
                timeout = PIP_INSTALL_TIMEOUT_MS
            )

            if (fallbackResult.exitCode != 0) {
                return Result.failure(
                    RuntimeException(
                        Strings.lsp_toolchain_error_pip_failed.strOr(
                            context,
                            fallbackResult.stderr.ifBlank { fallbackResult.stdout },
                        )
                    )
                )
            }
        }

        return verifyAndReturn(config, progress)
    }

    private suspend fun installNpm(
        config: LspToolchainConfig,
        progress: (LspInstallProgress) -> Unit
    ): Result<Unit> {
        val packages = config.packages
        if (packages.isNullOrEmpty()) {
            return Result.failure(IllegalArgumentException(Strings.lsp_toolchain_error_no_npm_packages.strOr(context)))
        }
        if (!LspToolchainPackagePolicy.areValid("npm", packages)) {
            return Result.failure(
                IllegalArgumentException(Strings.lsp_toolchain_error_packages_invalid.strOr(context, config.id))
            )
        }

        progress(
            LspInstallProgress(
                phase = Strings.lsp_toolchain_phase_installing_node_packages.strOr(context),
                progress = 0.3f,
                toolchainId = config.id,
                message = packages.joinToString(", ")
            )
        )

        val linuxEnvironment = linuxEnvironmentProvider.get()
        val command = mutableListOf("npm", "install", "-g")
        command.addAll(packages)

        val result = linuxEnvironment.execute(
            command = command,
            workDir = "/",
            timeout = PIP_INSTALL_TIMEOUT_MS
        )

        if (result.exitCode != 0) {
            return Result.failure(
                RuntimeException(
                    Strings.lsp_toolchain_error_npm_failed.strOr(
                        context,
                        result.stderr.ifBlank { result.stdout },
                    )
                )
            )
        }

        return verifyAndReturn(config, progress)
    }

    private suspend fun verifyAndReturn(
        config: LspToolchainConfig,
        progress: (LspInstallProgress) -> Unit
    ): Result<Unit> {
        progress(
            LspInstallProgress(
                phase = Strings.lsp_toolchain_phase_verifying_installation.strOr(context),
                progress = 0.9f,
                toolchainId = config.id
            )
        )

        if (!isInstalled(config)) {
            return Result.failure(RuntimeException(Strings.lsp_toolchain_error_verify_failed.strOr(context, config.id)))
        }

        progress(
            LspInstallProgress(
                phase = Strings.lsp_toolchain_phase_completed.strOr(context),
                progress = 1.0f,
                toolchainId = config.id
            )
        )

        return Result.success(Unit)
    }

    private suspend fun downloadFile(
        url: String,
        target: File,
        progress: (Long, Long) -> Unit
    ) {
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).executeCancellable().use { response ->
            if (!response.request.url.isHttps) {
                throw RuntimeException(Strings.lsp_toolchain_error_https_required.strOr(context))
            }
            if (!response.isSuccessful) {
                throw RuntimeException(
                    Strings.lsp_toolchain_error_download_failed.strOr(context, response.code, response.message)
                )
            }

            val body = response.body ?: throw RuntimeException(Strings.lsp_toolchain_error_empty_download_body.strOr(context))
            val contentLength = body.contentLength()
            if (contentLength > MAX_DOWNLOAD_BYTES) {
                throw RuntimeException(Strings.lsp_toolchain_error_download_too_large.strOr(context))
            }

            FileOutputStream(target).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var downloaded = 0L
                    var read: Int

                    while (input.read(buffer).also { read = it } != -1) {
                        if (read.toLong() > MAX_DOWNLOAD_BYTES - downloaded) {
                            throw RuntimeException(Strings.lsp_toolchain_error_download_too_large.strOr(context))
                        }
                        output.write(buffer, 0, read)
                        downloaded += read
                        progress(downloaded, contentLength)
                    }
                }
            }
        }
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isHttpsUrl(url: String): Boolean = runCatching {
        java.net.URI(url).let { uri ->
            uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null
        }
    }.getOrDefault(false)

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / (1024 * 1024)} MB"
    }

    private fun LspToolchainConfig.normalizedType(): String = type.trim().lowercase()

    private fun LspToolchainConfig.resolvePackages(packageManager: RootfsPackageManager): List<String> {
        val managerPackages = packagesByManager.orEmpty()
        val managerKey = packageManager.displayName()
        val explicitPackages = managerPackages[managerKey]
            ?: managerPackages[managerKey.uppercase()]
            ?: managerPackages[packageManager.name]
            ?: managerPackages[packageManager.name.lowercase()]
        return explicitPackages?.normalizedPackageList()
            ?: packages.normalizedPackageList()
    }

    private fun List<String>?.normalizedPackageList(): List<String> = orEmpty()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()

    private fun LinuxDistroRootfsHealthProbe.toDisplayName(): String = when (this) {
        LinuxDistroRootfsHealthProbe.ROOTFS_AVAILABLE ->
            Strings.linux_distro_health_probe_rootfs_available.strOr(context)
        LinuxDistroRootfsHealthProbe.PACKAGE_MANAGER_COMMANDS ->
            Strings.linux_distro_health_probe_package_manager_commands.strOr(context)
        LinuxDistroRootfsHealthProbe.PACKAGE_MANAGER_VERSION ->
            Strings.linux_distro_health_probe_package_manager_version.strOr(context)
        LinuxDistroRootfsHealthProbe.REQUIRED_BOOTSTRAP_COMMANDS ->
            Strings.linux_distro_health_probe_required_commands.strOr(context)
        LinuxDistroRootfsHealthProbe.OPTIONAL_BOOTSTRAP_COMMANDS ->
            Strings.linux_distro_health_probe_optional_commands.strOr(context)
        LinuxDistroRootfsHealthProbe.ARCHITECTURE ->
            Strings.linux_distro_health_probe_architecture.strOr(context)
        LinuxDistroRootfsHealthProbe.OS_RELEASE ->
            Strings.linux_distro_health_probe_os_release.strOr(context)
    }

    private fun RootfsPackageManager.supportsEqualsVersionFallback(): Boolean = this == RootfsPackageManager.APK || this == RootfsPackageManager.APT
}
