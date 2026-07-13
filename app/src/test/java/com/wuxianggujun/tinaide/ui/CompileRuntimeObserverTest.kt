package com.wuxianggujun.tinaide.ui

import android.app.Application
import com.wuxianggujun.tinaide.core.compile.CompileProjectUseCase
import com.wuxianggujun.tinaide.core.compile.ProcessManager
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CompileRuntimeObserverTest {

    @Test
    fun `handleProcessStateChanged maps process state before delegating`() = runTest {
        val helper = mockk<CompileActionsHelper>(relaxed = true)
        val synchronizer = mockk<CompileRuntimeObserver.FileTreeSynchronizer>(relaxed = true)
        val observer = CompileRuntimeObserver(helper, synchronizer)

        observer.handleProcessStateChanged(ProcessManager.ProcessState.RUNNING)

        coVerify(exactly = 1) {
            helper.handleProcessStateChanged(CompileActionsHelper.ExecutionProcessState.RUNNING)
        }
    }

    @Test
    fun `handleCompileEvent delegates success and syncs file tree`() = runTest {
        val helper = mockk<CompileActionsHelper>(relaxed = true)
        val synchronizer = mockk<CompileRuntimeObserver.FileTreeSynchronizer>(relaxed = true)
        val observer = CompileRuntimeObserver(helper, synchronizer)
        val event = CompileEvent.Success(
            CompileProjectUseCase.Report(
                action = CompileProjectUseCase.Action.BUILD,
                summary = "done",
                artifact = CompileProjectUseCase.BuildArtifact(
                    path = "/tmp/demo",
                    exportedPath = "/tmp/.tinaide/artifacts/demo",
                    kind = CompileProjectUseCase.BuildArtifactKind.EXECUTABLE
                )
            )
        )

        observer.handleCompileEvent(event)

        coVerify(exactly = 1) { helper.handleCompileSuccess(event) }
        coVerify(exactly = 1) {
            synchronizer.refreshAndRevealExportedArtifact("/tmp/.tinaide/artifacts/demo")
        }
    }

    @Test
    fun `handleCompileEvent delegates error without syncing file tree`() = runTest {
        val helper = mockk<CompileActionsHelper>(relaxed = true)
        val synchronizer = mockk<CompileRuntimeObserver.FileTreeSynchronizer>(relaxed = true)
        val observer = CompileRuntimeObserver(helper, synchronizer)
        val event = CompileEvent.Error(
            action = CompileProjectUseCase.Action.BUILD,
            message = "failed",
            throwable = null
        )

        observer.handleCompileEvent(event)

        coVerify(exactly = 1) { helper.handleCompileError(event) }
        coVerify(exactly = 0) { synchronizer.refreshAndRevealExportedArtifact(any()) }
    }
}
