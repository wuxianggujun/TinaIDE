package com.wuxianggujun.tinaide.ui

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.compile.CompileProjectUseCase
import com.wuxianggujun.tinaide.core.compile.ProcessManager
import com.wuxianggujun.tinaide.file.IProjectContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CompilerViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun compile_convertsUnexpectedThrowableToDurableErrorEvent() = runTest {
        val compileUseCase = mockk<CompileProjectUseCase>()
        coEvery {
            compileUseCase.execute(any(), any(), any(), any(), any())
        } throws IllegalStateException("compiler exploded")
        val viewModel = CompilerViewModel(
            compileUseCase = compileUseCase,
            projectContext = mockk<IProjectContext>(relaxed = true),
            processManager = ProcessManager(),
        )

        // 故意在任务结束后才订阅，验证生命周期空窗期间事件不会丢失。
        viewModel.compile(CompileProjectUseCase.Operation.forBuild())
        advanceUntilIdle()
        val event = viewModel.events.first() as CompileEvent.Error

        assertThat(event.action).isEqualTo(CompileProjectUseCase.Action.BUILD)
        assertThat(event.message).isEqualTo("compiler exploded")
        assertThat(event.throwable).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `rapid repeated compile calls do not replace a pending result`() = runTest {
        val useCase = mockk<CompileProjectUseCase>()
        coEvery { useCase.execute(any(), any(), any(), any(), any()) } returns success("first")
        val viewModel = viewModel(useCase)
        repeat(20) { viewModel.compile() }
        advanceUntilIdle()
        repeat(20) { viewModel.compile() }
        coVerify(exactly = 1) { useCase.execute(any(), any(), any(), any(), any()) }
        val event = viewModel.events.first()
        assertThat(event.ticket.isCurrent).isTrue()
        viewModel.cancelCompile()
        assertThat(event.ticket.claimResult()).isFalse()
    }

    @Test
    fun `restart waits for cancelled compilation cleanup and discards its result`() = runTest {
        val useCase = mockk<CompileProjectUseCase>()
        val releaseOld = CompletableDeferred<Unit>()
        var executions = 0
        var oldCleaned = false
        coEvery { useCase.execute(any(), any(), any(), any(), any()) } coAnswers {
            executions++
            if (executions == 1) {
                withContext(NonCancellable) { releaseOld.await() }
                oldCleaned = true
                success("old")
            } else {
                check(oldCleaned)
                success("new")
            }
        }
        val viewModel = viewModel(useCase)
        viewModel.compile()
        runCurrent()
        viewModel.cancelCompile()
        viewModel.compile()
        runCurrent()
        assertThat(executions).isEqualTo(1)
        releaseOld.complete(Unit)
        advanceUntilIdle()

        assertThat(executions).isEqualTo(2)
        val event = viewModel.events.first() as CompileEvent.Success
        assertThat(event.report.summary).isEqualTo("new")
        assertThat(event.ticket.isCurrent).isTrue()
    }

    private fun viewModel(useCase: CompileProjectUseCase) = CompilerViewModel(
        compileUseCase = useCase,
        projectContext = mockk(relaxed = true),
        processManager = ProcessManager(),
    )

    @Test fun courseAssessmentCannotOverlapAnotherBuild() = runTest {
        val useCase = mockk<CompileProjectUseCase>()
        val viewModel = viewModel(useCase)
        val ticket = requireNotNull(viewModel.actionGate.tryBegin())
        val attempted = runCatching { viewModel.assessCourse("cpp_basics", mockk(relaxed = true)) }
        assertThat(attempted.isFailure).isTrue()
        assertThat(ticket.isCurrent).isTrue()
        coVerify(exactly = 0) { useCase.execute(any(), any(), any(), any(), any()) }
        ticket.finish()
    }

    @Test fun assessmentSavesBuildsRunsAndReleasesTheGate() = runTest {
        val root = temporary.newFolder()
        java.io.File(root, "CMakeLists.txt").writeText("project(native_lab)\nadd_executable(native_lab main.cpp)")
        java.io.File(root, "main.cpp").writeText("int main() {}")
        val project = com.wuxianggujun.tinaide.file.Project("course", "native_lab", root.path, root.path, emptyList(), java.io.File(root, "build").path)
        val context = mockk<IProjectContext> { every { getCurrentProject() } returns project }
        val editor = mockk<com.wuxianggujun.tinaide.editor.IEditorManager>(relaxed = true)
        val useCase = mockk<CompileProjectUseCase>()
        val report = CompileProjectUseCase.Report(CompileProjectUseCase.Action.REBUILD_RUN, "built",
            launch = CompileProjectUseCase.LaunchSpec.Terminal("run", "executable", root.path, verificationCommand = "check"))
        coEvery { useCase.execute(any(), any(), any(), any(), any()) } returns CompileProjectUseCase.Result.Success(report)
        coEvery { useCase.checkConsoleOutput(report, "") } returns CompileProjectUseCase.ConsoleCheckResult(0, "total=30\n", false)
        val viewModel = spyk(CompilerViewModel(useCase, context, ProcessManager()))
        val config = com.wuxianggujun.tinaide.core.compile.RunConfiguration()
        coEvery { viewModel.getRunConfiguration() } returns config
        val result = viewModel.assessCourse("cpp_raii", editor)
        assertThat(result.passed).isTrue()
        assertThat(viewModel.actionGate.isBusy.value).isFalse()
        coVerify(exactly = 1) { editor.saveAll(any()) }
        coVerify(exactly = 1) { useCase.execute(any(), any(), config, any(), any()) }
        coVerify(exactly = 1) { useCase.checkConsoleOutput(report, "") }
    }

    private fun success(summary: String) = CompileProjectUseCase.Result.Success(
        CompileProjectUseCase.Report(action = CompileProjectUseCase.Action.RUN, summary = summary),
    )
}
