package com.wuxianggujun.tinaide.core.linuxdesktop

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.linux.LinuxEnvironment
import com.wuxianggujun.tinaide.core.linux.LinuxExecutionResult
import com.wuxianggujun.tinaide.core.linux.LinuxInteractiveProcess
import java.io.InputStream
import java.io.OutputStream
import org.junit.Test

class LinuxDesktopSessionLauncherTest {

    @Test
    fun launch_shouldUseEndpointOwnedDisplayAndAudioEnvironment() {
        val process = RecordingInteractiveProcess()
        val environment = RecordingLinuxEnvironment(process = process)
        val launcher = LinuxDesktopSessionLauncher(environment)

        val session = launcher.launch(
            endpoint = LinuxDesktopEndpoint(
                display = ":3",
                audioServer = "tcp:127.0.0.1:4713",
                environment = mapOf(
                    "GALLIUM_DRIVER" to "virpipe",
                    "DISPLAY" to ":99",
                ),
            ),
            spec = LinuxDesktopLaunchSpec(
                command = listOf("dbus-run-session", "startxfce4"),
                workingDirectory = "/root",
                environment = mapOf(
                    "LANG" to "zh_CN.UTF-8",
                    "PULSE_SERVER" to "invalid-session-value",
                ),
            ),
        ).getOrThrow()

        assertThat(environment.startedCommand).containsExactly(
            "dbus-run-session",
            "startxfce4",
        ).inOrder()
        assertThat(environment.startedWorkingDirectory).isEqualTo("/root")
        assertThat(environment.startedEnvironment).containsExactlyEntriesIn(
            mapOf(
                "LANG" to "zh_CN.UTF-8",
                "GALLIUM_DRIVER" to "virpipe",
                "DISPLAY" to ":3",
                "PULSE_SERVER" to "tcp:127.0.0.1:4713",
            )
        )
        assertThat(session.isRunning()).isTrue()

        session.close()
        session.close()

        assertThat(process.destroyCount).isEqualTo(1)
    }

    @Test
    fun launch_shouldFailBeforeStartingProcessWhenLinuxEnvironmentIsUnavailable() {
        val environment = RecordingLinuxEnvironment(available = false)

        val result = LinuxDesktopSessionLauncher(environment).launch(
            endpoint = LinuxDesktopEndpoint(display = ":0"),
            spec = LinuxDesktopLaunchSpec(command = listOf("startxfce4")),
        )

        assertThat(result.exceptionOrNull()).hasMessageThat().contains("unavailable")
        assertThat(environment.startedCommand).isEmpty()
    }

    @Test
    fun endpoint_shouldRejectInvalidDisplayAndEnvironmentKeys() {
        val invalidDisplay = runCatching { LinuxDesktopEndpoint(display = "not-a-display") }
        val invalidEnvironment = runCatching {
            LinuxDesktopEndpoint(
                display = ":0",
                environment = mapOf("INVALID-NAME" to "value"),
            )
        }

        assertThat(invalidDisplay.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(invalidEnvironment.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun ubuntuLauncher_shouldKeepX11AndRuntimeValuesManaged() {
        val process = RecordingInteractiveProcess()
        val environment = RecordingLinuxEnvironment(process = process)

        UbuntuDesktopSessionLauncher(environment).launch(
            UbuntuDesktopSessionOptions(
                endpoint = LinuxDesktopEndpoint(display = ":7"),
                username = "dev",
                locale = "zh_CN.UTF-8",
                gpuBackend = UbuntuDesktopGpuBackend.VIRGL,
                environment = mapOf(
                    "DISPLAY" to ":99",
                    "XDG_RUNTIME_DIR" to "/tmp/attacker-owned",
                    "GDK_BACKEND" to "wayland",
                    "MESA_LOADER_DRIVER_OVERRIDE" to "llvmpipe",
                ),
            ),
        ).getOrThrow()

        assertThat(environment.startedEnvironment["DISPLAY"]).isEqualTo(":7")
        assertThat(environment.startedEnvironment["XDG_RUNTIME_DIR"]).isEqualTo("/tmp/runtime-dev")
        assertThat(environment.startedEnvironment["GDK_BACKEND"]).isEqualTo("x11")
        assertThat(environment.startedEnvironment["QT_QPA_PLATFORM"]).isEqualTo("xcb")
        assertThat(environment.startedEnvironment["MESA_LOADER_DRIVER_OVERRIDE"]).isEqualTo("virpipe")
        assertThat(environment.startedEnvironment["GALLIUM_DRIVER"]).isEqualTo("virpipe")
    }

    @Test
    fun ubuntuSessionOptions_shouldRejectUnsafeIdentityAndLocale() {
        assertThat(
            runCatching {
                UbuntuDesktopSessionOptions(
                    endpoint = LinuxDesktopEndpoint(display = ":0"),
                    username = "root;id",
                )
            }.exceptionOrNull()
        ).isInstanceOf(IllegalArgumentException::class.java)

        assertThat(
            runCatching {
                UbuntuDesktopSessionOptions(
                    endpoint = LinuxDesktopEndpoint(display = ":0"),
                    locale = "C UTF-8",
                )
            }.exceptionOrNull()
        ).isInstanceOf(IllegalArgumentException::class.java)
    }

    private class RecordingLinuxEnvironment(
        private val available: Boolean = true,
        private val process: LinuxInteractiveProcess = RecordingInteractiveProcess(),
    ) : LinuxEnvironment {
        var startedCommand: List<String> = emptyList()
            private set
        var startedWorkingDirectory: String = ""
            private set
        var startedEnvironment: Map<String, String> = emptyMap()
            private set

        override fun isAvailable(): Boolean = available

        override suspend fun execute(
            command: List<String>,
            workDir: String,
            env: Map<String, String>,
            timeout: Long?,
            stdin: String?,
        ): LinuxExecutionResult = error("execute is not used in this test")

        override fun startInteractive(
            command: List<String>,
            workDir: String,
            env: Map<String, String>,
        ): LinuxInteractiveProcess {
            startedCommand = command
            startedWorkingDirectory = workDir
            startedEnvironment = env
            return process
        }

        override fun toGuestPath(hostPath: String): String = hostPath
    }

    private class RecordingInteractiveProcess : LinuxInteractiveProcess {
        var destroyCount: Int = 0
            private set

        override val stdin: OutputStream = OutputStream.nullOutputStream()
        override val stdout: InputStream = InputStream.nullInputStream()
        override val stderr: InputStream = InputStream.nullInputStream()

        override fun isRunning(): Boolean = destroyCount == 0

        override fun waitFor(timeout: Long): Int = 0

        override fun destroy() {
            destroyCount += 1
        }
    }
}
