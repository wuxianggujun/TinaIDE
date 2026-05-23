package com.wuxianggujun.tinaide.ai.tools.executor.execution

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DefaultExecutionCallbacksTest {

    @Test
    fun `default run build and test callbacks create failed results with retrievable output`() {
        val callbacks = DefaultExecutionCallbacks()

        val run = callbacks.runProject(RunRequest(configuration = "Debug"))
        val tests = callbacks.runTests(TestRequest(testClass = "ExampleTest"))
        val build = callbacks.buildProject(BuildRequest(clean = true))

        listOf(run, tests, build).forEach { result ->
            assertThat(result.executionId).isNotEmpty()
            assertThat(result.success).isFalse()
            assertThat(result.status).isEqualTo(ExecutionStatus.FAILED)
            assertThat(callbacks.getExecutionStatus(result.executionId)).isEqualTo(ExecutionStatus.FAILED)
            assertThat(callbacks.getExecutionOutput(result.executionId)?.exitCode).isEqualTo(-1)
        }
    }

    @Test
    fun `stop and clear execution status update lifecycle maps`() {
        val callbacks = DefaultExecutionCallbacks()
        val executionId = "running-execution"
        callbacks.updateExecutionStatus(executionId, ExecutionStatus.RUNNING)

        assertThat(callbacks.stopExecution(executionId)).isTrue()
        assertThat(callbacks.getExecutionStatus(executionId)).isEqualTo(ExecutionStatus.CANCELLED)

        callbacks.clearExecutionStatus(executionId)

        assertThat(callbacks.getExecutionStatus(executionId)).isNull()
        assertThat(callbacks.getExecutionOutput(executionId)).isNull()
        assertThat(callbacks.stopExecution(executionId)).isFalse()
    }

    @Test
    fun `default failed executions cannot be stopped as running work`() {
        val callbacks = DefaultExecutionCallbacks()
        val results = listOf(
            callbacks.runProject(RunRequest()),
            callbacks.runTests(TestRequest()),
            callbacks.buildProject(BuildRequest())
        )

        results.forEach { result ->
            assertThat(callbacks.stopExecution(result.executionId)).isFalse()
            assertThat(callbacks.getExecutionStatus(result.executionId)).isEqualTo(ExecutionStatus.FAILED)
        }
    }

    @Test
    fun `pending status can be stopped while terminal status cannot`() {
        val callbacks = DefaultExecutionCallbacks()
        val pendingId = "pending-execution"
        val failedId = "failed-execution"

        callbacks.updateExecutionStatus(pendingId, ExecutionStatus.PENDING)
        callbacks.updateExecutionStatus(failedId, ExecutionStatus.FAILED)

        assertThat(callbacks.stopExecution(pendingId)).isTrue()
        assertThat(callbacks.getExecutionStatus(pendingId)).isEqualTo(ExecutionStatus.CANCELLED)
        assertThat(callbacks.stopExecution(failedId)).isFalse()
        assertThat(callbacks.getExecutionStatus(failedId)).isEqualTo(ExecutionStatus.FAILED)
    }

    @Test
    fun `navigation callbacks are safe no ops`() {
        val callbacks = DefaultExecutionCallbacks()

        callbacks.navigateToRunOutput()
        callbacks.navigateToBuildLog()

        assertThat(callbacks.getExecutionStatus("missing")).isNull()
    }

    @Test
    fun `default build errors are empty`() {
        val callbacks = DefaultExecutionCallbacks()

        val result = callbacks.getBuildErrors()

        assertThat(result.hasErrors).isFalse()
        assertThat(result.errorCount).isEqualTo(0)
        assertThat(result.warningCount).isEqualTo(0)
        assertThat(result.errors).isEmpty()
    }
}
