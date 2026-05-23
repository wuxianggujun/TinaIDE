package com.wuxianggujun.tinaide.ai.integration

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.ai.tools.executor.execution.ExecutionResult
import com.wuxianggujun.tinaide.ai.tools.executor.execution.ExecutionStatus
import com.wuxianggujun.tinaide.core.compile.CompileProjectUseCase
import com.wuxianggujun.tinaide.core.compile.ProcessManager
import com.wuxianggujun.tinaide.core.compile.RunConfigurationManager
import com.wuxianggujun.tinaide.editor.IEditorManager
import com.wuxianggujun.tinaide.output.IOutputManager
import com.wuxianggujun.tinaide.ui.BottomPanelController
import com.wuxianggujun.tinaide.ui.BottomPanelViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Test

class ExecutionCallbacksImplTest {

    @Test
    fun `stopExecution returns false for unknown execution id without stopping process`() {
        val processManager = mockk<ProcessManager>(relaxed = true)
        val callbacks = newCallbacks(processManager)

        val stopped = callbacks.stopExecution("missing-execution")

        assertThat(stopped).isFalse()
        verify(exactly = 0) { processManager.stopCurrentProcess() }
    }

    @Test
    fun `stopExecution returns false for terminal execution without stopping process`() {
        val processManager = mockk<ProcessManager>(relaxed = true)
        val callbacks = newCallbacks(processManager)
        callbacks.putExecutionState("failed-execution", ExecutionStatus.FAILED)

        val stopped = callbacks.stopExecution("failed-execution")

        assertThat(stopped).isFalse()
        assertThat(callbacks.getExecutionStatus("failed-execution")).isEqualTo(ExecutionStatus.FAILED)
        verify(exactly = 0) { processManager.stopCurrentProcess() }
    }

    @Test
    fun `stopExecution cancels running and pending executions through process manager`() {
        val processManager = mockk<ProcessManager>()
        every { processManager.stopCurrentProcess() } returns true
        val callbacks = newCallbacks(processManager)
        callbacks.putExecutionState("running-execution", ExecutionStatus.RUNNING)
        callbacks.setActiveProcessExecutionId("running-execution")
        callbacks.putExecutionResult(
            ExecutionResult(
                executionId = "running-execution",
                success = true,
                exitCode = 0,
                output = "started",
                errorOutput = "",
                duration = 0,
                status = ExecutionStatus.RUNNING
            )
        )
        callbacks.putExecutionState("pending-execution", ExecutionStatus.PENDING)

        val stoppedRunning = callbacks.stopExecution("running-execution")
        callbacks.setActiveProcessExecutionId("pending-execution")
        val stoppedPending = callbacks.stopExecution("pending-execution")

        assertThat(stoppedRunning).isTrue()
        assertThat(stoppedPending).isTrue()
        assertThat(callbacks.getExecutionStatus("running-execution")).isEqualTo(ExecutionStatus.CANCELLED)
        assertThat(callbacks.getExecutionStatus("pending-execution")).isEqualTo(ExecutionStatus.CANCELLED)
        assertThat(callbacks.getExecutionOutput("running-execution")?.status)
            .isEqualTo(ExecutionStatus.CANCELLED)
        assertThat(callbacks.getExecutionOutput("running-execution")?.errorOutput)
            .isEqualTo("Execution cancelled by user")
        verify(exactly = 2) { processManager.stopCurrentProcess() }
    }

    @Test
    fun `stopExecution does not stop process for stale active state`() {
        val processManager = mockk<ProcessManager>(relaxed = true)
        val callbacks = newCallbacks(processManager)
        callbacks.putExecutionState("old-execution", ExecutionStatus.RUNNING)
        callbacks.putExecutionState("new-execution", ExecutionStatus.RUNNING)
        callbacks.setActiveProcessExecutionId("new-execution")

        val stopped = callbacks.stopExecution("old-execution")

        assertThat(stopped).isFalse()
        assertThat(callbacks.getExecutionStatus("old-execution")).isEqualTo(ExecutionStatus.RUNNING)
        assertThat(callbacks.getExecutionStatus("new-execution")).isEqualTo(ExecutionStatus.RUNNING)
        verify(exactly = 0) { processManager.stopCurrentProcess() }
    }

    @Test
    fun `stopExecution returns false when process manager cannot stop active process`() {
        val processManager = mockk<ProcessManager>()
        every { processManager.stopCurrentProcess() } returns false
        val callbacks = newCallbacks(processManager)
        callbacks.putExecutionState("running-execution", ExecutionStatus.RUNNING)
        callbacks.setActiveProcessExecutionId("running-execution")
        callbacks.putExecutionResult(
            ExecutionResult(
                executionId = "running-execution",
                success = true,
                exitCode = 0,
                output = "started",
                errorOutput = "",
                duration = 0,
                status = ExecutionStatus.RUNNING
            )
        )

        val stopped = callbacks.stopExecution("running-execution")

        assertThat(stopped).isFalse()
        assertThat(callbacks.getExecutionStatus("running-execution")).isEqualTo(ExecutionStatus.RUNNING)
        assertThat(callbacks.getExecutionOutput("running-execution")?.status)
            .isEqualTo(ExecutionStatus.RUNNING)
        verify(exactly = 1) { processManager.stopCurrentProcess() }
    }

    @Test
    fun `terminal completion does not overwrite cancelled execution`() {
        val callbacks = newCallbacks(mockk<ProcessManager>(relaxed = true))
        callbacks.putExecutionState("cancelled-execution", ExecutionStatus.CANCELLED)
        callbacks.putExecutionResult(
            ExecutionResult(
                executionId = "cancelled-execution",
                success = false,
                exitCode = 0,
                output = "started",
                errorOutput = "Execution cancelled by user",
                duration = 0,
                status = ExecutionStatus.CANCELLED
            )
        )

        val completed = callbacks.completeExecutionIfActiveForTest(
            ExecutionResult(
                executionId = "cancelled-execution",
                success = true,
                exitCode = 0,
                output = "finished",
                errorOutput = "",
                duration = 10,
                status = ExecutionStatus.SUCCESS
            )
        )

        assertThat(completed).isFalse()
        assertThat(callbacks.getExecutionStatus("cancelled-execution")).isEqualTo(ExecutionStatus.CANCELLED)
        assertThat(callbacks.getExecutionOutput("cancelled-execution")?.status)
            .isEqualTo(ExecutionStatus.CANCELLED)
        assertThat(callbacks.getExecutionOutput("cancelled-execution")?.output)
            .isEqualTo("started")
    }

    private fun newCallbacks(
        processManager: ProcessManager
    ): ExecutionCallbacksImpl = ExecutionCallbacksImpl(
        projectRoot = ".",
        processManager = processManager,
        runConfigManager = RunConfigurationManager(),
        editorManager = mockk<IEditorManager>(relaxed = true),
        outputManager = mockk<IOutputManager>(relaxed = true),
        compileProjectUseCase = mockk<CompileProjectUseCase>(relaxed = true),
        scope = CoroutineScope(SupervisorJob()),
        bottomPanelViewModel = mockk<BottomPanelViewModel>(relaxed = true),
        bottomPanelController = mockk<BottomPanelController>(relaxed = true)
    )

    private fun ExecutionCallbacksImpl.putExecutionState(
        executionId: String,
        status: ExecutionStatus
    ) {
        executionStatesForTest()[executionId] = status
    }

    private fun ExecutionCallbacksImpl.putExecutionResult(result: ExecutionResult) {
        executionResultsForTest()[result.executionId] = result
    }

    private fun ExecutionCallbacksImpl.setActiveProcessExecutionId(executionId: String) {
        activeProcessExecutionIdForTest().set(executionId)
    }

    private fun ExecutionCallbacksImpl.completeExecutionIfActiveForTest(result: ExecutionResult): Boolean {
        val method = ExecutionCallbacksImpl::class.java.getDeclaredMethod(
            "completeExecutionIfActive",
            ExecutionResult::class.java
        )
        method.isAccessible = true
        return method.invoke(this, result) as Boolean
    }

    @Suppress("UNCHECKED_CAST")
    private fun ExecutionCallbacksImpl.executionStatesForTest(): ConcurrentHashMap<String, ExecutionStatus> =
        privateField("executionStates") as ConcurrentHashMap<String, ExecutionStatus>

    @Suppress("UNCHECKED_CAST")
    private fun ExecutionCallbacksImpl.executionResultsForTest(): ConcurrentHashMap<String, ExecutionResult> =
        privateField("executionResults") as ConcurrentHashMap<String, ExecutionResult>

    @Suppress("UNCHECKED_CAST")
    private fun ExecutionCallbacksImpl.activeProcessExecutionIdForTest(): AtomicReference<String?> =
        privateField("activeProcessExecutionId") as AtomicReference<String?>

    private fun ExecutionCallbacksImpl.privateField(name: String): Any {
        val field = ExecutionCallbacksImpl::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(this)
    }
}
