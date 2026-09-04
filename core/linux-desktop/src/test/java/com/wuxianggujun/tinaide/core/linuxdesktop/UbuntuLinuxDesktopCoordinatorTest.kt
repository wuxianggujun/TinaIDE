package com.wuxianggujun.tinaide.core.linuxdesktop

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.linux.LinuxEnvironment
import com.wuxianggujun.tinaide.core.linux.LinuxEnvironmentProvider
import com.wuxianggujun.tinaide.core.linux.LinuxExecutionResult
import com.wuxianggujun.tinaide.core.linux.LinuxInteractiveProcess
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class UbuntuLinuxDesktopCoordinatorTest {

    @Test
    fun startSession_shouldFailClosedWhenLinuxEnvironmentIsUnavailable() = runTest {
        val desktop = FakeLinuxDesktopService()
        val coordinator = UbuntuLinuxDesktopCoordinator(
            linuxEnvironmentProvider = provider(RecordingLinuxEnvironment(available = false)),
            desktopService = desktop,
        )

        val result = coordinator.startSession()

        assertThat(result.isFailure).isTrue()
        assertThat(desktop.startCount).isEqualTo(0)
        assertThat(result.exceptionOrNull()?.message).contains("unavailable")
    }

    @Test
    fun startSession_shouldNotLaunchGuestWhenDesktopPackagesAreMissing() = runTest {
        val desktop = FakeLinuxDesktopService()
        val environment = RecordingLinuxEnvironment(availableCommands = emptySet())
        val coordinator = UbuntuLinuxDesktopCoordinator(
            linuxEnvironmentProvider = provider(environment),
            desktopService = desktop,
        )

        val result = coordinator.startSession()

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("not installed")
        assertThat(desktop.startCount).isEqualTo(0)
        assertThat(environment.startedCommand).isEmpty()
    }

    @Test
    fun startSession_shouldNotLaunchGuestWhenXServerFails() = runTest {
        val desktop = FakeLinuxDesktopService(
            startResult = Result.failure(IllegalStateException("socket missing")),
        )
        val environment = RecordingLinuxEnvironment()
        val coordinator = UbuntuLinuxDesktopCoordinator(
            linuxEnvironmentProvider = provider(environment),
            desktopService = desktop,
        )

        val result = coordinator.startSession()

        assertThat(result.isFailure).isTrue()
        assertThat(desktop.startCount).isEqualTo(1)
        assertThat(environment.startedCommand).isEmpty()
        assertThat(environment.startedEnvironment).doesNotContainKey("DISPLAY")
    }

    @Test
    fun startSession_shouldInjectRunningDisplayIntoGuestSession() = runTest {
        val desktop = FakeLinuxDesktopService(display = ":2")
        val environment = RecordingLinuxEnvironment()
        val coordinator = UbuntuLinuxDesktopCoordinator(
            linuxEnvironmentProvider = provider(environment),
            desktopService = desktop,
        )

        val session = coordinator.startSession(display = ":2").getOrThrow()

        assertThat(session.isRunning()).isTrue()
        assertThat(environment.startedCommand).contains("startxfce4")
        assertThat(environment.startedEnvironment["DISPLAY"]).isEqualTo(":2")
    }

    @Test
    fun startSession_shouldReuseARunningSession() = runTest {
        val desktop = FakeLinuxDesktopService()
        val environment = RecordingLinuxEnvironment()
        val coordinator = UbuntuLinuxDesktopCoordinator(
            linuxEnvironmentProvider = provider(environment),
            desktopService = desktop,
        )

        val first = coordinator.startSession().getOrThrow()
        val second = coordinator.startSession().getOrThrow()

        assertThat(second).isSameInstanceAs(first)
        assertThat(desktop.startCount).isEqualTo(1)
        assertThat(environment.startInteractiveCount).isEqualTo(1)
    }

    private fun provider(environment: LinuxEnvironment): LinuxEnvironmentProvider =
        object : LinuxEnvironmentProvider {
            override fun get(): LinuxEnvironment = environment
        }

    private class FakeLinuxDesktopService(
        private val startResult: Result<Unit> = Result.success(Unit),
        private val display: String = ":0",
    ) : LinuxDesktopService {
        private val _serverState = MutableStateFlow<X11ServerState>(X11ServerState.Stopped)
        override val serverState: StateFlow<X11ServerState> = _serverState

        var startCount: Int = 0
            private set

        override suspend fun startX11Server(
            display: String,
            config: X11DisplayConfig,
        ): Result<Unit> {
            startCount += 1
            return startResult.also { result ->
                _serverState.value = if (result.isSuccess) {
                    X11ServerState.Running(this.display)
                } else {
                    X11ServerState.Error(result.exceptionOrNull()?.message ?: "failed")
                }
            }
        }

        override suspend fun stopX11Server() {
            _serverState.value = X11ServerState.Stopped
        }

        override fun getX11EnvironmentVariables(): Map<String, String> {
            val state = _serverState.value
            return if (state is X11ServerState.Running) {
                mapOf("DISPLAY" to state.display)
            } else {
                emptyMap()
            }
        }
    }

    private class RecordingLinuxEnvironment(
        private val available: Boolean = true,
        private val availableCommands: Set<String> = setOf(
            "dbus-run-session",
            "startxfce4",
            "pactl",
            "fcitx5",
            "glxinfo",
        ),
        private val process: LinuxInteractiveProcess = RecordingInteractiveProcess(),
    ) : LinuxEnvironment {
        var startedCommand: List<String> = emptyList()
            private set
        var startedEnvironment: Map<String, String> = emptyMap()
            private set
        var startInteractiveCount: Int = 0
            private set

        override fun isAvailable(): Boolean = available

        override suspend fun execute(
            command: List<String>,
            workDir: String,
            env: Map<String, String>,
            timeout: Long?,
            stdin: String?,
        ): LinuxExecutionResult {
            val script = command.lastOrNull().orEmpty()
            val probed = COMMAND_PROBE.find(script)?.groupValues?.getOrNull(1)
            val exitCode = if (probed != null && probed in availableCommands) 0 else 1
            return LinuxExecutionResult(
                exitCode = exitCode,
                stdout = "",
                stderr = "",
                durationMs = 1L,
            )
        }

        override fun startInteractive(
            command: List<String>,
            workDir: String,
            env: Map<String, String>,
        ): LinuxInteractiveProcess {
            startInteractiveCount += 1
            startedCommand = command
            startedEnvironment = env
            return process
        }

        override fun toGuestPath(hostPath: String): String = hostPath
    }

    private class RecordingInteractiveProcess : LinuxInteractiveProcess {
        override val stdin: OutputStream = OutputStream.nullOutputStream()
        override val stdout: InputStream = InputStream.nullInputStream()
        override val stderr: InputStream = InputStream.nullInputStream()
        override fun isRunning(): Boolean = true
        override fun waitFor(timeout: Long): Int = 0
        override fun destroy() = Unit
    }

    private companion object {
        val COMMAND_PROBE = Regex("command -v '([^']+)'")
    }
}
