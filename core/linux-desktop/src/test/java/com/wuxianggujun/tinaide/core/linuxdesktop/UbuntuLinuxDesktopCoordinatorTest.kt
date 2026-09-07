package com.wuxianggujun.tinaide.core.linuxdesktop

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.linux.LinuxEnvironment
import com.wuxianggujun.tinaide.core.linux.LinuxEnvironmentProvider
import com.wuxianggujun.tinaide.core.linux.LinuxExecutionResult
import com.wuxianggujun.tinaide.core.linux.LinuxInteractiveProcess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class UbuntuLinuxDesktopCoordinatorTest {

    @Test
    fun startSession_shouldFailClosedWhenLinuxEnvironmentIsUnavailable() = runTest {
        val desktop = FakeLinuxDesktopService()
        val coordinator = coordinator(
            environment = RecordingLinuxEnvironment(available = false),
            desktop = desktop,
        )

        val result = coordinator.startSession()

        assertThat(result.isFailure).isTrue()
        assertThat(desktop.startCount).isEqualTo(0)
        assertThat(result.exceptionOrNull()?.message).contains("unavailable")
    }

    @Test
    fun startSession_shouldNotLaunchGuestWhenDesktopPackagesAreMissing() = runTest {
        val desktop = FakeLinuxDesktopService()
        val coordinator = coordinator(
            environment = RecordingLinuxEnvironment(availableCommands = emptySet()),
            desktop = desktop,
        )

        val result = coordinator.startSession()

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("not installed")
        assertThat(desktop.startCount).isEqualTo(0)
        assertThat(desktop.guestPlans).isEmpty()
    }

    @Test
    fun startSession_shouldNotLaunchGuestWhenXServerFails() = runTest {
        val desktop = FakeLinuxDesktopService(
            startResult = Result.failure(IllegalStateException("socket missing")),
        )
        val coordinator = coordinator(desktop = desktop)

        val result = coordinator.startSession()

        assertThat(result.isFailure).isTrue()
        assertThat(desktop.startCount).isEqualTo(1)
        assertThat(desktop.guestPlans).isEmpty()
    }

    @Test
    fun startSession_shouldFailWhenPRootHostPlannerIsUnavailable() = runTest {
        // rootfs 未安装时算不出 host 命令行。必须明确失败，而不是让 :x11 去 exec
        // 一条拼不出来的命令，那只会表现为桌面"启动了但立刻消失"。
        val desktop = FakeLinuxDesktopService()
        val coordinator = coordinator(desktop = desktop, hostPlanner = null)

        val result = coordinator.startSession()

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("planner is unavailable")
        assertThat(desktop.guestPlans).isEmpty()
    }

    @Test
    fun startSession_shouldSpawnGuestSessionInTheXServerProcessWithRunningDisplay() = runTest {
        val desktop = FakeLinuxDesktopService(display = ":2")
        val coordinator = coordinator(desktop = desktop)

        coordinator.startSession(display = ":2").getOrThrow()

        // 关键回归点：会话必须交给 X server 所在进程去 spawn。init-proot.sh 带
        // --kill-on-exit，在主进程 spawn 就等于"关掉 IDE 就杀掉桌面"。
        val plan = desktop.guestPlans.single()
        assertThat(plan.argv).contains("startxfce4")
        assertThat(plan.environment["DISPLAY"]).isEqualTo(":2")
        assertThat(coordinator.isSessionActive()).isTrue()
    }

    @Test
    fun startSession_shouldReuseARunningSession() = runTest {
        val desktop = FakeLinuxDesktopService()
        val coordinator = coordinator(desktop = desktop)

        coordinator.startSession().getOrThrow()
        coordinator.startSession().getOrThrow()

        assertThat(desktop.startCount).isEqualTo(1)
        assertThat(desktop.guestPlans).hasSize(1)
    }

    @Test
    fun stopSession_shouldStopTheGuestSessionBeforeTheXServer() = runTest {
        val desktop = FakeLinuxDesktopService()
        val coordinator = coordinator(desktop = desktop)
        coordinator.startSession().getOrThrow()

        coordinator.stopSession()

        // 反序会让 XFCE 对着已消失的 display 疯狂重连，把重启预算白烧掉。
        assertThat(desktop.stopOrder).containsExactly("guest", "server").inOrder()
        assertThat(coordinator.isSessionActive()).isFalse()
    }

    @Test
    fun sessionPhase_shouldComeFromTheXServerProcess() = runTest {
        val desktop = FakeLinuxDesktopService(phase = LinuxDesktopSupervisorPhase.FAILED)
        val coordinator = coordinator(desktop = desktop)

        assertThat(coordinator.sessionPhase()).isEqualTo(LinuxDesktopSupervisorPhase.FAILED)
    }

    @Test
    fun sessionPhase_shouldFallBackToIdleWhenTheXServerProcessIsGone() = runTest {
        val desktop = FakeLinuxDesktopService(phase = null)
        val coordinator = coordinator(desktop = desktop)

        assertThat(coordinator.sessionPhase()).isEqualTo(LinuxDesktopSupervisorPhase.IDLE)
    }

    private fun coordinator(
        environment: LinuxEnvironment = RecordingLinuxEnvironment(),
        desktop: LinuxDesktopService,
        hostPlanner: LinuxDesktopHostProcessPlanner? = RecordingHostProcessPlanner(),
    ) = UbuntuLinuxDesktopCoordinator(
        linuxEnvironmentProvider = object : LinuxEnvironmentProvider {
            override fun get(): LinuxEnvironment = environment
        },
        desktopService = desktop,
        hostProcessPlannerProvider = { hostPlanner },
    )

    /**
     * 模拟 `:core:proot` 侧的 proot 包装：把 guest 命令原样接到 init-proot.sh 后面。
     * 只保留真实实现的结构，不复制它的 proot 细节。
     */
    private class RecordingHostProcessPlanner : LinuxDesktopHostProcessPlanner {
        override fun plan(guestPlan: LinuxDesktopGuestPlan) = LinuxDesktopHostProcessPlan(
            argv = listOf("/system/bin/sh", "/data/init-proot.sh") + guestPlan.command,
            environment = guestPlan.environment + mapOf("ROOTFS_PATH" to "/data/rootfs"),
            workingDirectory = "/data/files",
        )
    }

    private class FakeLinuxDesktopService(
        private val startResult: Result<Unit> = Result.success(Unit),
        private val display: String = ":0",
        private val phase: LinuxDesktopSupervisorPhase? = LinuxDesktopSupervisorPhase.RUNNING,
    ) : LinuxDesktopService {
        private val _serverState = MutableStateFlow<X11ServerState>(X11ServerState.Stopped)
        override val serverState: StateFlow<X11ServerState> = _serverState

        var startCount: Int = 0
            private set

        val guestPlans: MutableList<LinuxDesktopHostProcessPlan> = mutableListOf()
        val stopOrder: MutableList<String> = mutableListOf()

        private var guestRunning = false

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
            stopOrder += "server"
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

        override suspend fun startGuestSession(
            plan: LinuxDesktopHostProcessPlan,
            restartPolicy: LinuxDesktopRestartPolicy,
        ): Result<Unit> {
            guestPlans += plan
            guestRunning = true
            return Result.success(Unit)
        }

        override suspend fun stopGuestSession() {
            stopOrder += "guest"
            guestRunning = false
        }

        override fun isGuestSessionRunning(): Boolean = guestRunning

        override fun guestSessionPhase(): LinuxDesktopSupervisorPhase? = phase
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
    ) : LinuxEnvironment {
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
            // 非探测脚本（UbuntuDesktopSessionPreparer 的 mkdir / machine-id）视为成功：
            // 那些命令的正确性由 preparer 自己的测试覆盖，这里只关心协同顺序。
            val exitCode = when {
                probed != null -> if (probed in availableCommands) 0 else 1
                else -> 0
            }
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
        ): LinuxInteractiveProcess =
            error("桌面会话必须在 :x11 进程 spawn，主进程不应调用 startInteractive")

        override fun toGuestPath(hostPath: String): String = hostPath
    }

    private companion object {
        val COMMAND_PROBE = Regex("command -v '([^']+)'")
    }
}
