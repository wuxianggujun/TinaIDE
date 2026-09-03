package com.wuxianggujun.tinaide.core.linuxdesktop

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.linux.LinuxEnvironment
import com.wuxianggujun.tinaide.core.linux.LinuxExecutionResult
import com.wuxianggujun.tinaide.core.linux.LinuxInteractiveProcess
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Test

class UbuntuDesktopProvisionerTest {

    @Test
    fun install_shouldNotRunAptWhenLinuxEnvironmentIsUnavailable() = runBlocking {
        val environment = FakeLinuxEnvironment(available = false)

        val result = UbuntuDesktopProvisioner(environment).install()

        assertThat(result.isFailure).isTrue()
        assertThat(environment.executions).isEmpty()
        Unit
    }

    @Test
    fun install_shouldBeIdempotentWhenAllDesktopCommandsArePresent() = runBlocking {
        val environment = FakeLinuxEnvironment(
            commands = REQUIRED_COMMANDS + OPTIONAL_COMMANDS,
        )

        val result = UbuntuDesktopProvisioner(environment).install()

        assertThat(result.isSuccess).isTrue()
        assertThat(environment.aptUpdateCount).isEqualTo(0)
        assertThat(environment.aptInstallCount).isEqualTo(0)
        assertThat(result.getOrThrow().installedPackages).isEmpty()
        Unit
    }

    @Test
    fun install_shouldUpdateAndInstallMissingDesktopPackages() = runBlocking {
        val environment = FakeLinuxEnvironment(installAddsDesktopCommands = true)

        val result = UbuntuDesktopProvisioner(environment).install()

        assertThat(result.isSuccess).isTrue()
        assertThat(environment.aptUpdateCount).isEqualTo(1)
        assertThat(environment.aptInstallCount).isEqualTo(1)
        assertThat(environment.installCommand).containsExactly(
            "apt-get",
            "install",
            "-y",
            "xfce4",
            "dbus-x11",
            "pulseaudio-utils",
            "fcitx5",
            "fcitx5-frontend-gtk3",
            "fcitx5-frontend-qt5",
            "mesa-utils",
            "xauth",
            "locales",
        ).inOrder()
        assertThat(environment.installEnvironment).containsExactlyEntriesIn(
            mapOf(
                "DEBIAN_FRONTEND" to "noninteractive",
                "NEEDRESTART_MODE" to "a",
            )
        )
        Unit
    }

    @Test
    fun install_shouldFailWhenRequiredCommandsRemainMissing() = runBlocking {
        val environment = FakeLinuxEnvironment(installAddsDesktopCommands = false)

        val result = UbuntuDesktopProvisioner(environment).install()

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageThat()
            .contains("still unavailable")
        assertThat(environment.aptUpdateCount).isEqualTo(1)
        assertThat(environment.aptInstallCount).isEqualTo(1)
        Unit
    }

    private class FakeLinuxEnvironment(
        private val available: Boolean = true,
        commands: List<String> = emptyList(),
        private val installAddsDesktopCommands: Boolean = false,
    ) : LinuxEnvironment {
        private val availableCommands = commands.toMutableSet()
        val executions = mutableListOf<Invocation>()
        var aptUpdateCount = 0
            private set
        var aptInstallCount = 0
            private set
        var installCommand: List<String> = emptyList()
            private set
        var installEnvironment: Map<String, String> = emptyMap()
            private set

        override fun isAvailable(): Boolean = available

        override suspend fun execute(
            command: List<String>,
            workDir: String,
            env: Map<String, String>,
            timeout: Long?,
            stdin: String?,
        ): LinuxExecutionResult {
            executions += Invocation(command, env)
            if (command == listOf("apt-get", "update")) {
                aptUpdateCount += 1
                return success()
            }
            if (command.take(2) == listOf("apt-get", "install")) {
                aptInstallCount += 1
                installCommand = command
                installEnvironment = env
                if (installAddsDesktopCommands) {
                    availableCommands += REQUIRED_COMMANDS
                    availableCommands += OPTIONAL_COMMANDS
                }
                return success()
            }

            if (command.take(2) == listOf("/bin/sh", "-lc")) {
                val script = command.getOrNull(2).orEmpty()
                val commandName = COMMAND_PROBE.find(script)?.groupValues?.getOrNull(1)
                if (commandName != null) {
                    return result(if (commandName in availableCommands) 0 else 1)
                }
                if (script.contains("test -e /dev/dri/renderD128")) return result(1)
                if (script.contains("virgl_test_server") || script.contains("virglrenderer_test")) {
                    return result(1)
                }
                if (script.contains("/usr/share/vulkan/icd.d")) return result(1)
            }
            return result(1)
        }

        override fun startInteractive(
            command: List<String>,
            workDir: String,
            env: Map<String, String>,
        ): LinuxInteractiveProcess = error("Interactive process is not used in provisioning tests")

        override fun toGuestPath(hostPath: String): String = hostPath

        private fun success(): LinuxExecutionResult = result(0)

        private fun result(exitCode: Int): LinuxExecutionResult = LinuxExecutionResult(
            exitCode = exitCode,
            stdout = "",
            stderr = if (exitCode == 0) "" else "command not found",
            durationMs = 1L,
        )
    }

    private data class Invocation(
        val command: List<String>,
        val environment: Map<String, String>,
    )

    private companion object {
        val REQUIRED_COMMANDS = listOf("dbus-run-session", "startxfce4")
        val OPTIONAL_COMMANDS = listOf("pactl", "fcitx5", "glxinfo")
        val COMMAND_PROBE = Regex("command -v '([^']+)'")
    }
}
