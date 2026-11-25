package com.wuxianggujun.tinaide.ui.runtime

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Test

class GraphicalRuntimeExitGuardTest {
    @Test
    fun `a running game has no deadline until exit is requested`() {
        val scheduled = mutableListOf<() -> Unit>()
        var terminations = 0
        val guard = GraphicalRuntimeExitGuard(
            terminateProcess = { terminations++ },
            schedule = { _, action -> scheduled += action },
        )

        // Construction and non-finishing Activity destruction must not arm a timer.
        guard.onDestroyed()

        assertThat(scheduled).isEmpty()
        assertThat(terminations).isEqualTo(0)
    }

    @Test
    fun `repeated finish calls schedule only one bounded shutdown`() {
        val scheduled = mutableListOf<() -> Unit>()
        var terminations = 0
        val guard = GraphicalRuntimeExitGuard(
            terminateProcess = { terminations++ },
            schedule = { delay, action ->
                assertThat(delay).isEqualTo(GraphicalRuntimeExitGuard.EXIT_GRACE_PERIOD_MS)
                scheduled += action
            },
        )

        repeat(3) { guard.requestExit() }
        assertThat(scheduled).hasSize(1)
        assertThat(terminations).isEqualTo(0)
        scheduled.single().invoke()
        guard.onDestroyed()

        assertThat(terminations).isEqualTo(1)
    }

    @Test
    fun `normal destruction terminates immediately without a later second kill`() {
        val scheduled = mutableListOf<() -> Unit>()
        var terminations = 0
        val guard = GraphicalRuntimeExitGuard(
            terminateProcess = { terminations++ },
            schedule = { _, action -> scheduled += action },
        )

        guard.requestExit()
        guard.onDestroyed()
        assertThat(terminations).isEqualTo(1)
        scheduled.single().invoke()

        assertThat(terminations).isEqualTo(1)
    }

    @Test
    fun `watchdog still runs while the caller is blocked in native destruction`() {
        val caller = Thread.currentThread()
        val worker = AtomicReference<Thread>()
        val terminated = CountDownLatch(1)
        val guard = GraphicalRuntimeExitGuard(
            terminateProcess = {
                worker.set(Thread.currentThread())
                terminated.countDown()
            },
            gracePeriodMillis = 10L,
        )

        guard.requestExit()
        // No main Looper is pumped here: NativeActivity.onDestroy can block it.
        assertThat(terminated.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(worker.get()).isNotSameInstanceAs(caller)
    }
}
