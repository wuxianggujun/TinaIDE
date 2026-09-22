package com.wuxianggujun.tinaide.core.linuxdesktop

import com.google.common.truth.Truth.assertThat
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
        val spawner = QueueSessionSpawner(listOf(firstProcess, secondProcess))
        val runningAfterRestart = CountDownLatch(1)

        val supervisor = LinuxDesktopSessionSupervisor(
            launchSession = { spawner.spawn() },
            restartPolicy = LinuxDesktopRestartPolicy(maxRestarts = 1, restartDelayMs = 0),
        )

        supervisor.start { status ->
            if (status.phase == LinuxDesktopSupervisorPhase.RUNNING && status.restartAttempt == 1) {
                runningAfterRestart.countDown()
            }
        }.getOrThrow()

        assertThat(runningAfterRestart.await(2, TimeUnit.SECONDS)).isTrue()
        assertThat(spawner.launchCount.get()).isEqualTo(2)
        assertThat(supervisor.status.phase).isEqualTo(LinuxDesktopSupervisorPhase.RUNNING)

        supervisor.stop()

        assertThat(secondProcess.destroyCount).isEqualTo(1)
        assertThat(supervisor.status.phase).isEqualTo(LinuxDesktopSupervisorPhase.STOPPED)
    }

    @Test
    fun supervisor_shouldFailAfterRestartBudgetIsExhausted() {
        val spawner = QueueSessionSpawner(
            listOf(
                FakeInteractiveProcess().apply { finish(1) },
                FakeInteractiveProcess().apply { finish(2) },
            )
        )
        val failed = CountDownLatch(1)

        val supervisor = LinuxDesktopSessionSupervisor(
            launchSession = { spawner.spawn() },
            restartPolicy = LinuxDesktopRestartPolicy(maxRestarts = 1, restartDelayMs = 0),
        )

        supervisor.start { status ->
            if (status.phase == LinuxDesktopSupervisorPhase.FAILED) failed.countDown()
        }.getOrThrow()

        assertThat(failed.await(2, TimeUnit.SECONDS)).isTrue()
        assertThat(spawner.launchCount.get()).isEqualTo(2)
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

    /**
     * 按顺序交出预置进程，模拟 `:x11` 侧每次重启 spawn 出一个新的 proot 进程。
     */
    private class QueueSessionSpawner(processes: List<FakeInteractiveProcess>) {
        private val processQueue = ArrayDeque(processes)
        val launchCount = AtomicInteger(0)

        fun spawn(): Result<LinuxDesktopSession> = runCatching {
            launchCount.incrementAndGet()
            LinuxDesktopSession(synchronized(processQueue) { processQueue.removeFirst() })
        }
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
