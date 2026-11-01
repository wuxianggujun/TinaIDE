package com.wuxianggujun.tinaide.ui

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.compile.CompileProjectUseCase
import com.wuxianggujun.tinaide.core.compile.ProcessManager
import com.wuxianggujun.tinaide.file.IProjectContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CompilerViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

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

    private fun success(summary: String) = CompileProjectUseCase.Result.Success(
        CompileProjectUseCase.Report(action = CompileProjectUseCase.Action.RUN, summary = summary),
    )
}
