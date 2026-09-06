package com.wuxianggujun.tinaide.core.linuxdesktop

import com.wuxianggujun.tinaide.core.linux.LinuxEnvironment
import com.wuxianggujun.tinaide.core.linux.LinuxExecutionResult
import kotlin.coroutines.cancellation.CancellationException

/**
 * Installs and verifies the Ubuntu guest pieces required by the desktop
 * session. This deliberately uses apt commands through [LinuxEnvironment]
 * instead of copying another project's rootfs or native binaries.
 */
class UbuntuDesktopProvisioner(
    private val linuxEnvironment: LinuxEnvironment,
    private val commandTimeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS,
    private val installTimeoutMs: Long = DEFAULT_INSTALL_TIMEOUT_MS,
) {
    enum class Phase {
        CHECKING,
        UPDATING_INDEX,
        INSTALLING_PACKAGES,
        VERIFYING,
        COMPLETED,
    }

    data class Progress(
        val phase: Phase,
        val progress: Float,
        val packages: List<String> = emptyList(),
    )

    data class Status(
        val environmentAvailable: Boolean,
        val requiredCommands: List<String>,
        val missingRequiredCommands: List<String>,
        val optionalCommands: List<String>,
        val missingOptionalCommands: List<String>,
        val gpuCapabilities: UbuntuDesktopGpuCapabilities = UbuntuDesktopGpuCapabilities(),
    ) {
        val ready: Boolean
            get() = environmentAvailable && missingRequiredCommands.isEmpty()
    }

    data class InstallResult(
        val installedPackages: List<String>,
        val status: Status,
    )

    suspend fun inspect(): Status {
        if (!linuxEnvironment.isAvailable()) {
            return Status(
                environmentAvailable = false,
                requiredCommands = REQUIRED_COMMANDS,
                missingRequiredCommands = REQUIRED_COMMANDS,
                optionalCommands = OPTIONAL_COMMANDS,
                missingOptionalCommands = OPTIONAL_COMMANDS,
            )
        }

        val missingRequired = REQUIRED_COMMANDS.filterNot { command -> commandAvailable(command) }
        val missingOptional = OPTIONAL_COMMANDS.filterNot { command -> commandAvailable(command) }
        return Status(
            environmentAvailable = true,
            requiredCommands = REQUIRED_COMMANDS,
            missingRequiredCommands = missingRequired,
            optionalCommands = OPTIONAL_COMMANDS,
            missingOptionalCommands = missingOptional,
            gpuCapabilities = UbuntuDesktopGpuDetector(linuxEnvironment).detect(),
        )
    }

    suspend fun install(
        progress: (Progress) -> Unit = {},
    ): Result<InstallResult> {
        return try {
            progress(Progress(Phase.CHECKING, 0.05f))
            check(linuxEnvironment.isAvailable()) { "Ubuntu Linux environment is unavailable" }
            val before = inspect()
            if (before.ready && before.missingOptionalCommands.isEmpty()) {
                progress(Progress(Phase.COMPLETED, 1f))
                return Result.success(InstallResult(emptyList(), before))
            }

            progress(Progress(Phase.UPDATING_INDEX, 0.20f))
            val updateResult = linuxEnvironment.execute(
                command = listOf("apt-get", "update"),
                workDir = "/",
                env = APT_ENVIRONMENT,
                timeout = commandTimeoutMs,
            )
            check(updateResult.isSuccess) {
                updateResult.failureMessage("Ubuntu desktop package index update failed")
            }

            progress(
                Progress(
                    phase = Phase.INSTALLING_PACKAGES,
                    progress = 0.45f,
                    packages = DESKTOP_PACKAGES,
                )
            )
            val installResult = linuxEnvironment.execute(
                command = listOf("apt-get", "install", "-y") + DESKTOP_PACKAGES,
                workDir = "/",
                env = APT_ENVIRONMENT,
                timeout = installTimeoutMs,
            )
            check(installResult.isSuccess) {
                installResult.failureMessage("Ubuntu desktop package installation failed")
            }

            progress(Progress(Phase.VERIFYING, 0.85f))
            val after = inspect()
            check(after.ready) {
                "Ubuntu desktop commands are still unavailable: ${after.missingRequiredCommands.joinToString()}"
            }
            progress(Progress(Phase.COMPLETED, 1f))
            Result.success(InstallResult(DESKTOP_PACKAGES, after))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private suspend fun commandAvailable(command: String): Boolean =
        linuxEnvironment.execute(
            command = listOf("/bin/sh", "-lc", "command -v ${shellQuote(command)} >/dev/null 2>&1"),
            workDir = "/",
            timeout = commandTimeoutMs,
        ).isSuccess

    private fun LinuxExecutionResult.failureMessage(fallback: String): String =
        combinedOutput.ifBlank { fallback }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private companion object {
        private const val DEFAULT_COMMAND_TIMEOUT_MS = 30_000L
        private const val DEFAULT_INSTALL_TIMEOUT_MS = 20L * 60L * 1000L
        private val APT_ENVIRONMENT = mapOf(
            "DEBIAN_FRONTEND" to "noninteractive",
            "NEEDRESTART_MODE" to "a",
        )
        private val REQUIRED_COMMANDS = listOf(
            "dbus-run-session",
            "startxfce4",
        )
        private val OPTIONAL_COMMANDS = listOf(
            "pactl",
            "fcitx5",
            "glxinfo",
        )
        private val DESKTOP_PACKAGES = listOf(
            "xfce4",
            "dbus-x11",
            // X11SocketLayout.hostXkbConfigRoot 指向 <rootfs>/usr/share/X11/xkb，
            // 缺了它 LinuxDesktopServiceImpl 会在启动 X server 前直接失败。
            "xkb-data",
            "pulseaudio-utils",
            "fcitx5",
            "fcitx5-frontend-gtk3",
            "fcitx5-frontend-qt5",
            "mesa-utils",
            "xauth",
            "locales",
        )
    }
}

data class UbuntuDesktopGpuCapabilities(
    val renderNodeAvailable: Boolean = false,
    val virglAvailable: Boolean = false,
    val vulkanLoaderAvailable: Boolean = false,
) {
    val accelerated: Boolean
        get() = renderNodeAvailable && (virglAvailable || vulkanLoaderAvailable)

    fun preferredBackend(): UbuntuDesktopGpuBackend = when {
        virglAvailable -> UbuntuDesktopGpuBackend.VIRGL
        vulkanLoaderAvailable -> UbuntuDesktopGpuBackend.ZINK
        else -> UbuntuDesktopGpuBackend.SOFTWARE
    }
}

private class UbuntuDesktopGpuDetector(
    private val linuxEnvironment: LinuxEnvironment,
) {
    suspend fun detect(): UbuntuDesktopGpuCapabilities = UbuntuDesktopGpuCapabilities(
        renderNodeAvailable = probe("test -e /dev/dri/renderD128"),
        virglAvailable = probe("command -v virgl_test_server >/dev/null 2>&1 || command -v virglrenderer_test >/dev/null 2>&1"),
        vulkanLoaderAvailable = probe("test -d /usr/share/vulkan/icd.d || command -v vulkaninfo >/dev/null 2>&1"),
    )

    private suspend fun probe(script: String): Boolean =
        linuxEnvironment.execute(
            command = listOf("/bin/sh", "-lc", script),
            workDir = "/",
            timeout = GPU_PROBE_TIMEOUT_MS,
        ).isSuccess

    private companion object {
        private const val GPU_PROBE_TIMEOUT_MS = 10_000L
    }
}
