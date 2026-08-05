package com.wuxianggujun.tinaide.core.packages.model

import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str

data class PackageInstallState(
    val linux: PlatformInstallState = PlatformInstallState.NotInstalled,
    val android: PlatformInstallState = PlatformInstallState.NotInstalled
) {
    fun forPlatform(platform: Platform): PlatformInstallState = when (platform) {
        Platform.LINUX -> linux
        Platform.ANDROID -> android
    }

    fun withPlatform(platform: Platform, state: PlatformInstallState): PackageInstallState = when (platform) {
        Platform.LINUX -> copy(linux = state)
        Platform.ANDROID -> copy(android = state)
    }
}

sealed class PlatformInstallState {
    object NotInstalled : PlatformInstallState()

    data class Installed(
        val version: String,
        val installedAt: Long = System.currentTimeMillis()
    ) : PlatformInstallState()

    data class Installing(val progress: Float) : PlatformInstallState()

    data class UpdateAvailable(
        val currentVersion: String,
        val newVersion: String
    ) : PlatformInstallState()

    val isInstalled: Boolean get() = this is Installed || this is UpdateAvailable
}

sealed class InstallProgressEvent {
    data class Preparing(val message: String) : InstallProgressEvent()

    data class Downloading(
        val downloaded: Long,
        val total: Long,
        val speed: Long
    ) : InstallProgressEvent() {
        val progress: Float get() = if (total > 0) downloaded.toFloat() / total else 0f
    }

    data class Verifying(val message: String) : InstallProgressEvent()

    data class Extracting(val progress: Float) : InstallProgressEvent()

    data class Installing(val message: String) : InstallProgressEvent()

    data class Completed(val result: InstallResult) : InstallProgressEvent()

    data class Failed(val error: InstallError) : InstallProgressEvent()
}

sealed class InstallResult {
    data class Success(
        val packageId: String,
        val version: String,
        val platform: Platform
    ) : InstallResult()

    data class Failure(
        val packageId: String,
        val error: InstallError
    ) : InstallResult()
}

sealed class InstallError {
    data class NetworkError(val message: String) : InstallError()
    data class SizeMismatch(val expected: Long, val actual: Long) : InstallError()
    data class ChecksumMismatch(val expected: String, val actual: String) : InstallError()
    data class DiskFull(val required: Long, val available: Long) : InstallError()
    data class ExtractionFailed(val message: String) : InstallError()
    data class DependencyMissing(val dependencies: List<String>) : InstallError()
    data class UnsupportedAbi(val currentAbi: String, val supportedAbis: List<String>) : InstallError()
    data class AptError(val exitCode: Int, val output: String) : InstallError()
    data class ScriptError(val exitCode: Int, val output: String) : InstallError()
    object Cancelled : InstallError()
    data class UnknownError(val message: String) : InstallError()

    fun toDisplayMessage(): String = when (this) {
        is NetworkError -> message
        is SizeMismatch -> Strings.pkg_manager_error_download_size_mismatch.str(expected, actual)
        is ChecksumMismatch -> "Checksum mismatch: expected $expected, got $actual"
        is DiskFull -> "Disk full: need $required bytes, available $available bytes"
        is ExtractionFailed -> message
        is DependencyMissing -> "Missing dependencies: ${dependencies.joinToString()}"
        is UnsupportedAbi -> "Unsupported device ABI: $currentAbi. Supported ABI: ${supportedAbis.joinToString()}"
        is AptError -> "Linux package manager error (exit $exitCode): $output"
        is ScriptError -> "Script error (exit $exitCode): $output"
        is Cancelled -> "Installation cancelled"
        is UnknownError -> message
    }
}

sealed class UninstallResult {
    data class Success(
        val packageId: String,
        val platform: Platform,
        val freedSpace: Long = 0
    ) : UninstallResult()

    data class Failure(
        val packageId: String,
        val error: UninstallError
    ) : UninstallResult()
}

sealed class UninstallError {
    data class PackageNotFound(val packageId: String) : UninstallError()
    data class DependentPackages(val dependents: List<String>) : UninstallError()
    data class PermissionDenied(val path: String) : UninstallError()
    data class InUse(val processName: String) : UninstallError()
    data class UnknownError(val message: String) : UninstallError()

    fun toDisplayMessage(): String = when (this) {
        is PackageNotFound -> "Package not found: $packageId"
        is DependentPackages -> "Cannot uninstall: ${dependents.joinToString()} depend on this package"
        is PermissionDenied -> "Permission denied: $path"
        is InUse -> "Package in use by: $processName"
        is UnknownError -> message
    }
}
