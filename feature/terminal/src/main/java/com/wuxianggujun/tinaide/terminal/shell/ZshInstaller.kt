package com.wuxianggujun.tinaide.terminal.shell

import android.content.Context
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.linux.LinuxEnvironmentProvider
import com.wuxianggujun.tinaide.core.linux.UnavailableLinuxEnvironmentProvider
import com.wuxianggujun.tinaide.core.proot.GuestSystemPackageManager
import com.wuxianggujun.tinaide.core.proot.RootfsPackageManager
import com.wuxianggujun.tinaide.core.proot.displayName
import com.wuxianggujun.tinaide.core.proot.resolveGuestPackageManager
import com.wuxianggujun.tinaide.core.terminal.IShellInstaller
import com.wuxianggujun.tinaide.core.terminal.ShellInstallResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ZshInstaller(
    private val context: Context,
    private val linuxEnvironmentProvider: LinuxEnvironmentProvider = UnavailableLinuxEnvironmentProvider,
) : IShellInstaller {

    override suspend fun isInstalled(): Boolean = withContext(Dispatchers.IO) {
        val linuxEnvironment = linuxEnvironmentProvider.get()
        if (!linuxEnvironment.isAvailable()) return@withContext false

        val result = linuxEnvironment.execute(
            command = listOf("/bin/sh", "-lc", "command -v zsh >/dev/null 2>&1"),
            workDir = "/",
            timeout = 10_000
        )
        result.exitCode == 0
    }

    override suspend fun install(
        force: Boolean,
        onProgress: (String) -> Unit
    ): ShellInstallResult = withContext(Dispatchers.IO) {
        val linuxEnvironment = linuxEnvironmentProvider.get()
        if (!linuxEnvironment.isAvailable()) {
            return@withContext ShellInstallResult.Error(Strings.zsh_error_proot_not_installed.strOr(context))
        }

        try {
            if (!force && isInstalled()) {
                return@withContext ShellInstallResult.Success
            }

            val packageManager = linuxEnvironment.resolveGuestPackageManager()
            if (packageManager == RootfsPackageManager.UNKNOWN) {
                return@withContext ShellInstallResult.Error(
                    Strings.zsh_error_unsupported_package_manager.strOr(
                        context,
                        packageManager.displayName()
                    )
                )
            }

            onProgress(Strings.zsh_updating_index.strOr(context))
            val update = GuestSystemPackageManager.updateIndex(
                linuxEnvironment = linuxEnvironment,
                packageManager = packageManager,
                timeoutMs = 120_000,
            )
            if (update.exitCode != 0) {
                return@withContext ShellInstallResult.Error(
                    Strings.zsh_error_update_failed.strOr(
                        context,
                        update.combinedOutput.ifBlank { "${packageManager.displayName()} update failed" }
                    )
                )
            }

            onProgress(Strings.zsh_installing.strOr(context))
            val install = GuestSystemPackageManager.installPackages(
                linuxEnvironment = linuxEnvironment,
                packageManager = packageManager,
                packages = listOf("zsh"),
                timeoutMs = 300_000,
                force = force,
            )
            if (install.exitCode != 0) {
                return@withContext ShellInstallResult.Error(
                    Strings.zsh_error_install_failed.strOr(
                        context,
                        install.combinedOutput.ifBlank { "${packageManager.displayName()} install zsh failed" }
                    )
                )
            }

            onProgress(Strings.zsh_verifying.strOr(context))
            if (isInstalled()) {
                ShellInstallResult.Success
            } else {
                ShellInstallResult.Error(Strings.zsh_error_verify_failed.strOr(context))
            }
        } catch (e: Exception) {
            ShellInstallResult.Error(
                Strings.zsh_error_exception.strOr(
                    context,
                    e.message ?: Strings.error_unknown.strOr(context)
                )
            )
        }
    }
}
