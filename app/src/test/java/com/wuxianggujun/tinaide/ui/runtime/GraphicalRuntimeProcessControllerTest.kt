package com.wuxianggujun.tinaide.ui.runtime

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class GraphicalRuntimeProcessControllerTest {
    @Test
    fun `switching engines clears every previous graphical process before staging`() = runTest {
        var processes = listOf(process(42, "$PACKAGE:gui"), process(43, "$PACKAGE:sdl"), process(44, "$PACKAGE:sdl2"))
        val killed = mutableListOf<Int>()
        val controller = GraphicalRuntimeProcessController(
            packageName = PACKAGE,
            ownerUid = UID,
            callerPid = 1,
            runningProcesses = { processes },
            killProcess = { pid ->
                killed += pid
                processes = processes.filterNot { it.pid == pid }
            },
        )

        assertThat(controller.stopPrevious()).isTrue()
        assertThat(killed).containsExactly(42, 43, 44)
    }

    @Test
    fun `unavailable process snapshot fails closed`() = runTest {
        val killed = mutableListOf<Int>()
        val controller = GraphicalRuntimeProcessController(
            packageName = PACKAGE,
            ownerUid = UID,
            callerPid = 1,
            runningProcesses = { error("Snapshot unavailable") },
            killProcess = killed::add,
        )

        assertThat(controller.stopPrevious()).isFalse()
        assertThat(killed).isEmpty()
    }

    @Test
    fun `restart only stops the exact owned GUI process and waits for its exit`() = runTest {
        val gui = process(42, "$PACKAGE:gui")
        var processes = listOf(
            process(1, PACKAGE),
            gui,
            process(43, "$PACKAGE:x11"),
            process(44, "$PACKAGE:gui-extra"),
            process(45, "other.app:gui"),
            process(46, "$PACKAGE:gui", UID + 1),
        )
        val killed = mutableListOf<Int>()
        val exitAllowed = CompletableDeferred<Unit>()
        val controller = GraphicalRuntimeProcessController(
            packageName = PACKAGE,
            ownerUid = UID,
            callerPid = 1,
            runningProcesses = { processes },
            killProcess = { pid ->
                killed += pid
                launch {
                    exitAllowed.await()
                    processes = processes - gui
                }
            },
        )

        val result = CompletableDeferred<Boolean>()
        val restart = launch { result.complete(controller.stopPrevious()) }
        testScheduler.runCurrent()
        assertThat(killed).containsExactly(42)
        assertThat(result.isCompleted).isFalse()

        exitAllowed.complete(Unit)
        restart.join()
        assertThat(result.await()).isTrue()
    }

    @Test
    fun `missing runtime is already safe and never kills the caller`() = runTest {
        val killed = mutableListOf<Int>()
        val controller = GraphicalRuntimeProcessController(
            packageName = PACKAGE,
            ownerUid = UID,
            callerPid = 42,
            runningProcesses = { listOf(process(42, "$PACKAGE:gui")) },
            killProcess = killed::add,
        )

        assertThat(controller.stopPrevious()).isTrue()
        assertThat(killed).isEmpty()
    }

    @Test
    fun `a stuck process refuses the next launch after a bounded wait`() = runTest {
        val killed = mutableListOf<Int>()
        val controller = GraphicalRuntimeProcessController(
            packageName = PACKAGE,
            ownerUid = UID,
            callerPid = 1,
            runningProcesses = { listOf(process(42, "$PACKAGE:gui")) },
            killProcess = killed::add,
        )

        assertThat(controller.stopPrevious()).isFalse()
        assertThat(killed).containsExactly(42)
    }

    @Test
    fun `a newly appeared GUI process is not killed by a previous restart request`() = runTest {
        var processes = listOf(process(42, "$PACKAGE:gui"))
        val killed = mutableListOf<Int>()
        val controller = GraphicalRuntimeProcessController(
            packageName = PACKAGE,
            ownerUid = UID,
            callerPid = 1,
            runningProcesses = { processes },
            killProcess = { pid ->
                killed += pid
                processes = listOf(process(43, "$PACKAGE:gui"))
            },
        )

        assertThat(controller.stopPrevious()).isFalse()
        assertThat(killed).containsExactly(42)
    }

    @Test
    fun `runtime ownership rejects IDE main other UID and invalid PID`() {
        for (suffix in listOf(":gui", ":sdl", ":sdl2")) {
            assertThat(GraphicalRuntimeProcessController.isOwnedRuntime(process(42, PACKAGE + suffix), PACKAGE, UID))
                .isTrue()
        }
        for (candidate in listOf(
            process(1, PACKAGE),
            process(42, "$PACKAGE:gui", UID + 1),
            process(0, "$PACKAGE:gui"),
            process(-1, "$PACKAGE:gui"),
        )) {
            assertThat(GraphicalRuntimeProcessController.isOwnedRuntime(candidate, PACKAGE, UID)).isFalse()
        }
    }

    private fun process(pid: Int, name: String, uid: Int = UID) =
        GraphicalRuntimeProcessController.ProcessInfo(pid, uid, name)

    private companion object {
        const val PACKAGE = "com.example.ide"
        const val UID = 10042
    }
}
