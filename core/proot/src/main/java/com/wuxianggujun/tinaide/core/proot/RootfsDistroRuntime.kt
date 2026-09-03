package com.wuxianggujun.tinaide.core.proot

import android.content.Context
import com.wuxianggujun.tinaide.core.config.IConfigManager
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.linux.UnavailableLinuxEnvironment
import com.wuxianggujun.tinaide.core.linuxdistro.DistroArchitecture
import com.wuxianggujun.tinaide.core.linuxdistro.DistroPackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 设置页使用的 Linux 发行版运行时门面。
 *
 * Linux rootfs 的列表和安装统一走自研 `:core:linux-distro` manifest，
 * 不再保留旧脚本运行时或灰度兼容入口。
 */
class RootfsDistroRuntime(
    context: Context,
    private val configManager: IConfigManager,
) {
    private val appContext = context.applicationContext

    data class DistroOption(
        val id: String,
        val displayName: String,
        val description: String,
        val packageManager: RootfsPackageManager,
        val releaseId: String = "",
        val version: String = "",
        val architecture: DistroArchitecture? = null,
        val sizeBytes: Long? = null,
    )

    data class InstallProgress(
        val progress: Float,
        val message: String,
        val completed: Boolean,
    )

    fun listDistros(): List<DistroOption> = runCatching {
        val architecture = SelfHostedLinuxDistroRuntime.defaultArchitecture()
        SelfHostedLinuxDistroRuntime.loadCachedOrBundledCatalog(appContext)
            .listInstallableDefaultArtifacts(architecture)
            .map { resolved ->
                val packageManager = resolved.distro.packageManager.toRootfsPackageManager()
                DistroOption(
                    id = resolved.distro.id,
                    displayName = resolved.distro.displayName,
                    description = Strings.linux_distro_self_hosted_option_desc.strOr(
                        appContext,
                        resolved.release.version.ifBlank { resolved.release.id },
                        packageManager.name.lowercase(),
                    ),
                    packageManager = packageManager,
                    releaseId = resolved.release.id,
                    version = resolved.release.version,
                    architecture = resolved.artifact.architecture,
                    sizeBytes = resolved.artifact.sizeBytes,
                )
            }
            .onlyBuiltInDistro(SelfHostedLinuxDistroRuntime.DEFAULT_DISTRO_ID)
    }.getOrElse { emptyList() }

    suspend fun installDistro(
        distroId: String,
        progress: (InstallProgress) -> Unit = {},
    ): Result<RootfsProfile> {
        require(distroId == SelfHostedLinuxDistroRuntime.DEFAULT_DISTRO_ID) {
            "Only the Ubuntu Linux distribution is supported by the built-in runtime"
        }
        return SelfHostedLinuxDistroRuntime.createForExplicitInstall(appContext, configManager)
            .installDistro(distroId = distroId) { installProgress ->
                progress(
                    InstallProgress(
                        progress = installProgress.progress.coerceIn(0f, 1f),
                        message = installProgress.message,
                        completed = installProgress.phase == SelfHostedLinuxDistroRuntime.Phase.COMPLETED,
                    )
                )
            }
    }

    suspend fun checkActiveDistroHealth(): Result<LinuxDistroRootfsHealthReport> = withContext(Dispatchers.IO) {
        runCatching {
            val store = RootfsProfileStore(appContext, configManager)
            val activeProfile = store.getActiveProfileForDistro(SelfHostedLinuxDistroRuntime.DEFAULT_DISTRO_ID)
            val linuxEnvironment = activeProfile
                ?.takeIf { profile -> store.isInstalled(profile) }
                ?.let { profile ->
                    PRootRootfsLinuxEnvironment(
                        context = appContext,
                        rootfsPath = profile.rootfsPath,
                    )
                } ?: UnavailableLinuxEnvironment
            LinuxDistroRootfsHealthChecker().check(
                linuxEnvironment = linuxEnvironment,
                packageManager = activeProfile?.packageManager ?: RootfsPackageManager.UNKNOWN,
            )
        }
    }

    private fun DistroPackageManager.toRootfsPackageManager(): RootfsPackageManager = when (this) {
        DistroPackageManager.APK -> RootfsPackageManager.APK
        DistroPackageManager.APT -> RootfsPackageManager.APT
        DistroPackageManager.PACMAN -> RootfsPackageManager.PACMAN
        DistroPackageManager.DNF -> RootfsPackageManager.DNF
        DistroPackageManager.ZYPPER,
        DistroPackageManager.XBPS,
        DistroPackageManager.UNKNOWN -> RootfsPackageManager.UNKNOWN
    }
}

internal fun List<RootfsDistroRuntime.DistroOption>.onlyBuiltInDistro(
    distroId: String,
): List<RootfsDistroRuntime.DistroOption> = filter { distro -> distro.id == distroId }
