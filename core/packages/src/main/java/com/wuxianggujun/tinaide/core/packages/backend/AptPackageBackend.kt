package com.wuxianggujun.tinaide.core.packages.backend

import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str
import com.wuxianggujun.tinaide.core.packages.model.*
import com.wuxianggujun.tinaide.core.proot.GuestSystemPackageManager
import com.wuxianggujun.tinaide.core.proot.PRootEnvironment
import java.util.concurrent.atomic.AtomicBoolean
import timber.log.Timber

class ApkPackageBackend(
    private val prootEnv: PRootEnvironment
) {
    companion object {
        private const val TAG = "ApkPackageBackend"
        private const val PACKAGE_TIMEOUT_MS = 300_000L
    }

    private val isInstalling = AtomicBoolean(false)

    suspend fun install(
        packageId: String,
        systemPackage: String,
        version: String,
        progress: (InstallProgressEvent) -> Unit
    ): InstallResult {
        if (!GuestSystemPackageManager.isSafePackageArgument(systemPackage)) {
            val error = InstallError.UnknownError(Strings.pkg_manager_error_system_package_invalid.str())
            progress(InstallProgressEvent.Failed(error))
            return InstallResult.Failure(packageId, error)
        }
        if (!isInstalling.compareAndSet(false, true)) {
            return InstallResult.Failure(
                packageId,
                InstallError.UnknownError("Another installation is in progress")
            )
        }

        try {
            if (!prootEnv.isInstalled()) {
                return InstallResult.Failure(
                    packageId,
                    InstallError.UnknownError("PRoot environment not installed")
                )
            }

            progress(InstallProgressEvent.Preparing("Checking if $systemPackage is already installed..."))

            if (isPackageInstalled(systemPackage)) {
                Timber.tag(TAG).d("Package $systemPackage is already installed")
                progress(InstallProgressEvent.Completed(InstallResult.Success(packageId, version, Platform.LINUX)))
                return InstallResult.Success(packageId, version, Platform.LINUX)
            }

            progress(InstallProgressEvent.Preparing("Updating package index..."))
            val packageManager = prootEnv.getActiveGuestPackageManager()

            val updateResult = GuestSystemPackageManager.updateIndex(
                linuxEnvironment = prootEnv,
                packageManager = packageManager,
                timeoutMs = PACKAGE_TIMEOUT_MS,
            )

            if (updateResult.exitCode != 0) {
                Timber.tag(TAG).w("%s update failed (exit %d), continuing anyway...", packageManager, updateResult.exitCode)
            }

            progress(InstallProgressEvent.Installing("Installing $systemPackage..."))

            val installResult = GuestSystemPackageManager.installPackages(
                linuxEnvironment = prootEnv,
                packageManager = packageManager,
                packages = listOf(systemPackage),
                timeoutMs = PACKAGE_TIMEOUT_MS,
            )

            return if (installResult.exitCode == 0) {
                Timber.tag(TAG).d("Successfully installed $systemPackage")
                progress(InstallProgressEvent.Completed(InstallResult.Success(packageId, version, Platform.LINUX)))
                InstallResult.Success(packageId, version, Platform.LINUX)
            } else {
                val errorOutput = installResult.combinedOutput.takeLast(500)
                Timber.tag(TAG).e("Failed to install $systemPackage: $errorOutput")
                val error = InstallError.AptError(installResult.exitCode, errorOutput)
                progress(InstallProgressEvent.Failed(error))
                InstallResult.Failure(packageId, error)
            }
        } finally {
            isInstalling.set(false)
        }
    }

    suspend fun uninstall(
        packageId: String,
        systemPackage: String
    ): UninstallResult {
        if (!GuestSystemPackageManager.isSafePackageArgument(systemPackage)) {
            return UninstallResult.Failure(
                packageId,
                UninstallError.UnknownError(Strings.pkg_manager_error_system_package_invalid.str())
            )
        }
        if (!prootEnv.isInstalled()) {
            return UninstallResult.Failure(
                packageId,
                UninstallError.UnknownError("PRoot environment not installed")
            )
        }

        val result = GuestSystemPackageManager.removePackages(
            linuxEnvironment = prootEnv,
            packageManager = prootEnv.getActiveGuestPackageManager(),
            packages = listOf(systemPackage),
            timeoutMs = PACKAGE_TIMEOUT_MS,
        )

        return if (result.exitCode == 0) {
            Timber.tag(TAG).d("Successfully uninstalled $systemPackage")
            UninstallResult.Success(packageId, Platform.LINUX, 0)
        } else {
            Timber.tag(TAG).e("Failed to uninstall $systemPackage: ${result.stderr}")
            UninstallResult.Failure(
                packageId,
                UninstallError.UnknownError("Package removal failed: ${result.stderr.takeLast(200)}")
            )
        }
    }

    suspend fun isPackageInstalled(systemPackage: String): Boolean {
        if (!GuestSystemPackageManager.isSafePackageArgument(systemPackage)) return false
        return prootEnv.queryInstalledPackageVersions(listOf(systemPackage))[systemPackage] != null
    }

    suspend fun getInstalledVersion(systemPackage: String): String? {
        if (!GuestSystemPackageManager.isSafePackageArgument(systemPackage)) return null
        return prootEnv.queryInstalledPackageVersions(listOf(systemPackage))[systemPackage]
    }
}
