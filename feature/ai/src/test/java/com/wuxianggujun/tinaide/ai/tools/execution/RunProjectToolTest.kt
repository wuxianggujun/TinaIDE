package com.wuxianggujun.tinaide.ai.tools.execution

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.ai.api.ToolCall
import com.wuxianggujun.tinaide.ai.api.ToolFunction
import com.wuxianggujun.tinaide.ai.tools.ToolExecutionContext
import com.wuxianggujun.tinaide.ai.tools.ToolExecutionResult
import com.wuxianggujun.tinaide.ai.tools.executor.execution.DefaultExecutionCallbacks
import com.wuxianggujun.tinaide.ai.tools.executor.execution.ExecutionResult
import com.wuxianggujun.tinaide.ai.tools.executor.execution.ExecutionStatus
import com.wuxianggujun.tinaide.ai.tools.executor.execution.RunRequest
import com.wuxianggujun.tinaide.ai.tools.installToolTestAppStrings
import com.wuxianggujun.tinaide.ai.tools.resetToolTestAppStrings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

class RunProjectToolTest {
    @Before
    fun setUpStrings() {
        installToolTestAppStrings()
    }

    @After
    fun tearDownStrings() {
        resetToolTestAppStrings()
    }

    @Test
    fun `execute forwards configuration arguments and working directory`(): Unit = runBlocking {
        val callbacks = RecordingExecutionCallbacks()
        val toolCall = ToolCall(
            id = "call-1",
            type = "function",
            function = ToolFunction(
                name = RunProjectTool.name,
                arguments = """{"configuration":"Debug","arguments":["--port","8080"],"working_directory":"/tmp/app"}"""
            )
        )

        val result = RunProjectTool.execute(
            toolCall,
            ToolExecutionContext(additionalData = mapOf("executionCallbacks" to callbacks))
        )

        assertThat(result).isInstanceOf(ToolExecutionResult.Success::class.java)
        assertThat(callbacks.lastRunRequest).isEqualTo(
            RunRequest(
                configuration = "Debug",
                arguments = listOf("--port", "8080"),
                workingDirectory = "/tmp/app"
            )
        )
    }

    @Test
    fun `execute formats completed output and metadata with truncation`(): Unit = runBlocking {
        val callbacks = RecordingExecutionCallbacks(
            executionResult = executionResult(
                success = true,
                status = ExecutionStatus.SUCCESS,
                output = "o".repeat(5001),
                duration = 123
            )
        )

        val result = RunProjectTool.execute(
            runToolCall("""{"configuration":"Release"}"""),
            ToolExecutionContext(additionalData = mapOf("executionCallbacks" to callbacks))
        )

        assertThat(result).isInstanceOf(ToolExecutionResult.Success::class.java)
        val success = result as ToolExecutionResult.Success
        assertThat(success.content).contains("Execution completed successfully")
        assertThat(success.content).contains("... (output truncated)")
        assertThat(success.metadata).containsExactly(
            "executionId",
            "exec-test",
            "exitCode",
            0,
            "duration",
            123L,
            "status",
            "SUCCESS"
        )
    }

    @Test
    fun `execute formats failed and non terminal statuses as tool errors`() = runBlocking {
        val failedCallbacks = RecordingExecutionCallbacks(
            executionResult = executionResult(
                success = false,
                status = ExecutionStatus.FAILED,
                exitCode = 2,
                errorOutput = "compiler error"
            )
        )
        val timeoutCallbacks = RecordingExecutionCallbacks(
            executionResult = executionResult(
                success = false,
                status = ExecutionStatus.TIMEOUT,
                output = "timed out"
            )
        )

        val failed = RunProjectTool.execute(
            runToolCall(),
            ToolExecutionContext(additionalData = mapOf("executionCallbacks" to failedCallbacks))
        )
        val timeout = RunProjectTool.execute(
            runToolCall(),
            ToolExecutionContext(additionalData = mapOf("executionCallbacks" to timeoutCallbacks))
        )

        assertThat(failed).isInstanceOf(ToolExecutionResult.Error::class.java)
        assertThat((failed as ToolExecutionResult.Error).message).contains("compiler error")
        assertThat(timeout).isInstanceOf(ToolExecutionResult.Error::class.java)
        assertThat((timeout as ToolExecutionResult.Error).message).contains("TIMEOUT")
    }

    @Test
    fun `execute returns error when execution callbacks are unavailable`(): Unit = runBlocking {
        val result = RunProjectTool.execute(
            runToolCall(),
            ToolExecutionContext()
        )

        assertThat(result).isInstanceOf(ToolExecutionResult.Error::class.java)
        assertThat((result as ToolExecutionResult.Error).message).contains("Execution callbacks not available")
    }

    @Test
    fun `execute returns error when default run callback is not implemented`(): Unit = runBlocking {
        val result = RunProjectTool.execute(
            runToolCall(),
            ToolExecutionContext(additionalData = mapOf("executionCallbacks" to DefaultExecutionCallbacks()))
        )

        assertThat(result).isInstanceOf(ToolExecutionResult.Error::class.java)
        val message = (result as ToolExecutionResult.Error).message
        assertThat(message).contains("Execution failed")
        assertThat(message).contains("Project execution not implemented yet")
        assertThat(message).doesNotContain("Project execution started")
    }

    private fun runToolCall(arguments: String = "{}"): ToolCall = ToolCall(
        id = "call-1",
        type = "function",
        function = ToolFunction(
            name = RunProjectTool.name,
            arguments = arguments
        )
    )

    private fun executionResult(
        success: Boolean,
        status: ExecutionStatus,
        output: String = "",
        errorOutput: String = "",
        exitCode: Int = 0,
        duration: Long = 0L
    ): ExecutionResult = ExecutionResult(
        executionId = "exec-test",
        success = success,
        exitCode = exitCode,
        output = output,
        errorOutput = errorOutput,
        duration = duration,
        status = status
    )
}
