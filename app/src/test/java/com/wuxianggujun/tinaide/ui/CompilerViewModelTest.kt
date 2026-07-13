package com.wuxianggujun.tinaide.ui

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.compile.CompileProjectUseCase
import com.wuxianggujun.tinaide.core.compile.ProcessManager
import com.wuxianggujun.tinaide.file.IProjectContext
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
}
