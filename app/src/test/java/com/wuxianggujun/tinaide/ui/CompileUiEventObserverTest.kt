package com.wuxianggujun.tinaide.ui

import android.app.Application
import com.wuxianggujun.tinaide.core.terminal.TerminalBackend
import com.wuxianggujun.tinaide.ui.runtime.GraphicalRuntimeLaunchRequest
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
                )
            )
        )
        observer.handleUiEvent(
            CompileActionsHelper.UiEvent.OpenTerminal(
                command = "cmake --build .",
                workDir = "/tmp/project",
                backend = TerminalBackend.HOST
            )
        )
        observer.handleUiEvent(
            CompileActionsHelper.UiEvent.OpenGraphicalRuntime(
                GraphicalRuntimeLaunchRequest.NativeActivity(
                    libraryPath = "/tmp/libraylib-demo.so",
                    environment = mapOf("LD_LIBRARY_PATH" to "/tmp/lib"),
                )
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
}
