package com.wuxianggujun.tinaide.ui

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.terminal.TerminalBackend
import com.wuxianggujun.tinaide.ui.runtime.GraphicalRuntimeLaunchRequest
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CompileUiEventObserverTest {

    @Test
    fun `handleUiEvent delegates toast events`() = runTest {
        val toastPresenter = mockk<CompileUiEventObserver.ToastPresenter>(relaxed = true)
        val graphicalRuntimeLauncher = mockk<CompileUiEventObserver.GraphicalRuntimeLauncher>(relaxed = true)
        val terminalLauncher = mockk<CompileUiEventObserver.TerminalLauncher>(relaxed = true)
        val projectTreeRevealer = mockk<CompileUiEventObserver.ProjectTreeRevealer>(relaxed = true)
        val observer = CompileUiEventObserver(
            toastPresenter = toastPresenter,
            graphicalRuntimeLauncher = graphicalRuntimeLauncher,
            terminalLauncher = terminalLauncher,
            projectTreeRevealer = projectTreeRevealer
        )

        observer.handleUiEvent(
            CompileActionsHelper.UiEvent.ShowToast(
                message = "done",
                type = CompileActionsHelper.ToastType.SUCCESS
            )
        )

        verify(exactly = 1) {
            toastPresenter.show("done", CompileActionsHelper.ToastType.SUCCESS)
        }
    }

    @Test
    fun `handleUiEvent delegates sdl and terminal launch events`() = runTest {
        val toastPresenter = mockk<CompileUiEventObserver.ToastPresenter>(relaxed = true)
        val graphicalRuntimeLauncher = mockk<CompileUiEventObserver.GraphicalRuntimeLauncher>(relaxed = true)
        val terminalLauncher = mockk<CompileUiEventObserver.TerminalLauncher>(relaxed = true)
        val projectTreeRevealer = mockk<CompileUiEventObserver.ProjectTreeRevealer>(relaxed = true)
        val observer = CompileUiEventObserver(
            toastPresenter = toastPresenter,
            graphicalRuntimeLauncher = graphicalRuntimeLauncher,
            terminalLauncher = terminalLauncher,
            projectTreeRevealer = projectTreeRevealer
        )

        observer.handleUiEvent(
            CompileActionsHelper.UiEvent.OpenGraphicalRuntime(
                GraphicalRuntimeLaunchRequest.Sdl(
                    libraryPath = "/tmp/libdemo.so",
                    environment = emptyMap(),
                ),
                ticket = launchTicket(),
            )
        )
        observer.handleUiEvent(
            CompileActionsHelper.UiEvent.OpenTerminal(
                command = "cmake --build .",
                workDir = "/tmp/project",
                backend = TerminalBackend.HOST,
                ticket = launchTicket(),
            )
        )
        observer.handleUiEvent(
            CompileActionsHelper.UiEvent.OpenGraphicalRuntime(
                GraphicalRuntimeLaunchRequest.NativeActivity(
                    libraryPath = "/tmp/libraylib-demo.so",
                    environment = mapOf("LD_LIBRARY_PATH" to "/tmp/lib"),
                ),
                ticket = launchTicket(),
            )
        )

        coVerify(exactly = 1) {
            graphicalRuntimeLauncher.open(
                GraphicalRuntimeLaunchRequest.Sdl(
                    libraryPath = "/tmp/libdemo.so",
                    environment = emptyMap(),
                )
            )
        }
        coVerify(exactly = 1) {
            graphicalRuntimeLauncher.open(
                GraphicalRuntimeLaunchRequest.NativeActivity(
                    libraryPath = "/tmp/libraylib-demo.so",
                    environment = mapOf("LD_LIBRARY_PATH" to "/tmp/lib"),
                )
            )
        }
        verify(exactly = 1) {
            terminalLauncher.open(
                command = "cmake --build .",
                workDir = "/tmp/project",
                backend = TerminalBackend.HOST
            )
        }
    }

    @Test
    fun `handleUiEvent delegates reveal requests`() = runTest {
        val toastPresenter = mockk<CompileUiEventObserver.ToastPresenter>(relaxed = true)
        val graphicalRuntimeLauncher = mockk<CompileUiEventObserver.GraphicalRuntimeLauncher>(relaxed = true)
        val terminalLauncher = mockk<CompileUiEventObserver.TerminalLauncher>(relaxed = true)
        val projectTreeRevealer = mockk<CompileUiEventObserver.ProjectTreeRevealer>(relaxed = true)
        val observer = CompileUiEventObserver(
            toastPresenter = toastPresenter,
            graphicalRuntimeLauncher = graphicalRuntimeLauncher,
            terminalLauncher = terminalLauncher,
            projectTreeRevealer = projectTreeRevealer
        )
        val file = File("/tmp/demo")

        observer.handleUiEvent(
            CompileActionsHelper.UiEvent.RevealInProjectTree(
                file = file,
                selectTarget = false
            )
        )

        coVerify(exactly = 1) {
            projectTreeRevealer.reveal(file, selectTarget = false)
        }
    }

    @Test
    fun `duplicate launch events consumed sequentially open only once`() = runTest {
        val opened = mutableListOf<GraphicalRuntimeLaunchRequest>()
        val observer = observer { opened += it }
        val event = graphicalEvent(launchTicket())
        repeat(20) { observer.handleUiEvent(event) }
        assertThat(opened).containsExactly(event.request)
    }

    @Test
    fun `old buffered launch cannot replace a newer session`() = runTest {
        val gate = CompileActionGate()
        val oldEvent = graphicalEvent(launchTicket(gate))
        gate.cancel()
        val newEvent = graphicalEvent(launchTicket(gate))
        val opened = mutableListOf<GraphicalRuntimeLaunchRequest>()
        val observer = observer { opened += it }

        observer.handleUiEvent(oldEvent)
        assertThat(gate.isBusy.value).isTrue()
        observer.handleUiEvent(newEvent)
        assertThat(opened).containsExactly(newEvent.request)
        assertThat(gate.isBusy.value).isFalse()
    }

    @Test
    fun `stop during staging cancels the launch before another request is allowed`() = runTest {
        val gate = CompileActionGate()
        val entered = CompletableDeferred<Unit>()
        var pendingIntentRevoked = false
        val observer = observer {
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                pendingIntentRevoked = true
            }
        }
        val consuming = launch { observer.handleUiEvent(graphicalEvent(launchTicket(gate))) }
        entered.await()
        gate.cancel()
        assertThat(gate.tryBegin()).isNull()
        runCurrent()
        consuming.join()
        assertThat(pendingIntentRevoked).isTrue()
        assertThat(gate.tryBegin()).isNotNull()
    }

    @Test
    fun `collector cancelled between receiving and handling releases the launch ticket`() = runTest {
        val gate = CompileActionGate()
        val events = Channel<CompileActionsHelper.UiEvent>(
            capacity = Channel.BUFFERED,
            onUndeliveredElement = CompileActionsHelper.UiEvent::releaseUndeliveredLaunch,
        )
        var opened = false
        val observer = observer { opened = true }
        val collecting = launch { events.receiveAsFlow().collect(observer::handleUiEvent) }
        runCurrent()
        assertThat(events.trySend(graphicalEvent(launchTicket(gate))).isSuccess).isTrue()
        collecting.cancel()
        runCurrent()
        assertThat(opened).isFalse()
        assertThat(gate.isBusy.value).isFalse()
        events.cancel()
    }

    private fun launchTicket(gate: CompileActionGate = CompileActionGate()): CompileActionGate.Ticket =
        checkNotNull(gate.tryBegin()).also {
            check(it.startCompilation())
            check(it.claimResult())
        }

    private fun graphicalEvent(ticket: CompileActionGate.Ticket) = CompileActionsHelper.UiEvent.OpenGraphicalRuntime(
        request = GraphicalRuntimeLaunchRequest.NativeActivity("/tmp/run-${ticket.id}/libmain.so"),
        ticket = ticket,
    )

    private fun observer(open: suspend (GraphicalRuntimeLaunchRequest) -> Unit) = CompileUiEventObserver(
        toastPresenter = mockk(relaxed = true),
        graphicalRuntimeLauncher = CompileUiEventObserver.GraphicalRuntimeLauncher { open(it) },
        terminalLauncher = mockk(relaxed = true),
        projectTreeRevealer = mockk(relaxed = true),
    )
}
