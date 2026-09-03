package com.wuxianggujun.tinaide.core.linuxdesktop

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.linux.LinuxEnvironment
import com.wuxianggujun.tinaide.core.linux.LinuxExecutionResult
import com.wuxianggujun.tinaide.core.linux.LinuxInteractiveProcess
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test

class LinuxDesktopSessionSupervisorTest {

    @Test
    fun supervisor_shouldRestartUnexpectedExitAndStopCurrentSession() {
        val firstProcess = FakeInteractiveProcess().apply { finish(7) }
        val secondProcess = FakeInteractiveProcess()
        val environment = QueueLinuxEnvironment(listOf(firstProcess, secondProcess))
        val launcher = LinuxDesktopSessionLauncher(environment)
        val runningAfterRestart = CountDownLatch(1)

        val supervisor = LinuxDesktopSessionSupervisor(
            launchSession = { launch(launcher) },
            restartPolicy = LinuxDesktopRestartPolicy(maxRestarts = 1, restartDelayMs = 0),
        )

        supervisor.start { status ->
            if (status.phase == LinuxDesktopSupervisorPhase.RUNNING && status.restartAttempt == 1) {
                runningAfterRestart.countDown()
            }
        }.getOrThrow()

        assertThat(runningAfterRestart.await(2, TimeUnit.SECONDS)).isTrue()
        assertThat(environment.launchCount.get()).isEqualTo(2)
        assertThat(supervisor.status.phase).isEqualTo(LinuxDesktopSupervisorPhase.RUNNING)

        supervisor.stop()

        assertThat(secondProcess.destroyCount).isEqualTo(1)
        assertThat(supervisor.status.phase).isEqualTo(LinuxDesktopSupervisorPhase.STOPPED)
    }

    @Test
    fun supervisor_shouldFailAfterRestartBudgetIsExhausted() {
        val environment = QueueLinuxEnvironment(
            listOf(
                FakeInteractiveProcess().apply { finish(1) },
                FakeInteractiveProcess().apply { finish(2) },
            )
        )
        val launcher = LinuxDesktopSessionLauncher(environment)
        val failed = CountDownLatch(1)

        val supervisor = LinuxDesktopSessionSupervisor(
            launchSession = { launch(launcher) },
            restartPolicy = LinuxDesktopRestartPolicy(maxRestarts = 1, restartDelayMs = 0),
        )

        supervisor.start { status ->
            if (status.phase == LinuxDesktopSupervisorPhase.FAILED) failed.countDown()
        }.getOrThrow()

        assertThat(failed.await(2, TimeUnit.SECONDS)).isTrue()
        assertThat(environment.launchCount.get()).isEqualTo(2)
        assertThat(supervisor.status.exitCode).isEqualTo(2)
        assertThat(supervisor.status.restartAttempt).isEqualTo(1)
    }

    @Test
    fun supervisor_shouldReportInitialLaunchFailureWithoutStartingWorker() {
        val launchError = IllegalStateException("display backend unavailable")
        val supervisor = LinuxDesktopSessionSupervisor(
            launchSession = { Result.failure(launchError) },
        )

        val result = supervisor.start()

        assertThat(result.exceptionOrNull()).isSameInstanceAs(launchError)
        assertThat(supervisor.status.phase).isEqualTo(LinuxDesktopSupervisorPhase.FAILED)
        assertThat(supervisor.status.failure).isSameInstanceAs(launchError)
    }

    private fun launch(launcher: LinuxDesktopSessionLauncher): Result<LinuxDesktopSession> = launcher.launch(
        endpoint = LinuxDesktopEndpoint(display = ":0"),
        spec = LinuxDesktopLaunchSpec(command = listOf("startxfce4")),
    )

    private class QueueLinuxEnvironment(
        processes: List<FakeInteractiveProcess>,
    ) : LinuxEnvironment {
        private val processQueue = ArrayDeque(processes)
        val launchCount = AtomicInteger(0)

        override fun isAvailable(): Boolean = true

        override suspend fun execute(
            command: List<String>,
            workDir: String,
            env: Map<String, String>,
            timeout: Long?,
            stdin: String?,
        ): LinuxExecutionResult = error("execute is not used")

        override fun startInteractive(
            command: List<String>,
            workDir: String,
            env: Map<String, String>,
        ): LinuxInteractiveProcess {
            launchCount.incrementAndGet()
            return synchronized(processQueue) { processQueue.removeFirst() }
        }

        override fun toGuestPath(hostPath: String): String = hostPath
    }

    private class FakeInteractiveProcess : LinuxInteractiveProcess {
        private val finished = CountDownLatch(1)

        @Volatile
        private var exitCode = 0

        @Volatile
        var destroyCount: Int = 0
            private set

        override val stdin: OutputStream = OutputStream.nullOutputStream()
        override val stdout: InputStream = InputStream.nullInputStream()
        override val stderr: InputStream = InputStream.nullInputStream()

        override fun isRunning(): Boolean = finished.count > 0

        override fun waitFor(timeout: Long): Int {
            if (timeout > 0L && !finished.await(timeout, TimeUnit.MILLISECONDS)) return -1
            if (timeout == 0L) finished.await()
            return exitCode
        }

        override fun destroy() {
            destroyCount += 1
            finish(143)
        }

        fun finish(code: Int) {
            exitCode = code
            finished.countDown()
        }
    }
}
