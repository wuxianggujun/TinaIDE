package com.wuxianggujun.tinaide.ui

import com.wuxianggujun.tinaide.core.terminal.TerminalBackend
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompileUiEventObserverTest {

    @Test
    fun `handleUiEvent delegates toast events`() = runTest {
        val toastPresenter = mockk<CompileUiEventObserver.ToastPresenter>(relaxed = true)
        val sdlLauncher = mockk<CompileUiEventObserver.SdlLauncher>(relaxed = true)
        val terminalLauncher = mockk<CompileUiEventObserver.TerminalLauncher>(relaxed = true)
        val projectTreeRevealer = mockk<CompileUiEventObserver.ProjectTreeRevealer>(relaxed = true)
        val observer = CompileUiEventObserver(
            toastPresenter = toastPresenter,
            sdlLauncher = sdlLauncher,
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
        val sdlLauncher = mockk<CompileUiEventObserver.SdlLauncher>(relaxed = true)
        val terminalLauncher = mockk<CompileUiEventObserver.TerminalLauncher>(relaxed = true)
        val projectTreeRevealer = mockk<CompileUiEventObserver.ProjectTreeRevealer>(relaxed = true)
        val observer = CompileUiEventObserver(
            toastPresenter = toastPresenter,
            sdlLauncher = sdlLauncher,
            terminalLauncher = terminalLauncher,
            projectTreeRevealer = projectTreeRevealer
        )

        observer.handleUiEvent(
            CompileActionsHelper.UiEvent.OpenSdl(
                libraryPath = "/tmp/libdemo.so",
                environment = emptyMap(),
            )
        )
        observer.handleUiEvent(
            CompileActionsHelper.UiEvent.OpenTerminal(
                command = "cmake --build .",
                workDir = "/tmp/project",
                backend = TerminalBackend.HOST
            )
        )

        coVerify(exactly = 1) { sdlLauncher.open("/tmp/libdemo.so", emptyMap()) }
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
        val sdlLauncher = mockk<CompileUiEventObserver.SdlLauncher>(relaxed = true)
        val terminalLauncher = mockk<CompileUiEventObserver.TerminalLauncher>(relaxed = true)
        val projectTreeRevealer = mockk<CompileUiEventObserver.ProjectTreeRevealer>(relaxed = true)
        val observer = CompileUiEventObserver(
            toastPresenter = toastPresenter,
            sdlLauncher = sdlLauncher,
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
