package com.wuxianggujun.tinaide.ai.tools.execution

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.ai.api.ToolCall
import com.wuxianggujun.tinaide.ai.api.ToolFunction
import com.wuxianggujun.tinaide.ai.tools.ToolExecutionContext
import com.wuxianggujun.tinaide.ai.tools.ToolExecutionResult
import com.wuxianggujun.tinaide.ai.tools.executor.execution.BuildRequest
import com.wuxianggujun.tinaide.ai.tools.executor.execution.DefaultExecutionCallbacks
import com.wuxianggujun.tinaide.ai.tools.executor.execution.ExecutionResult
import com.wuxianggujun.tinaide.ai.tools.executor.execution.ExecutionStatus
import com.wuxianggujun.tinaide.ai.tools.installToolTestAppStrings
import com.wuxianggujun.tinaide.ai.tools.resetToolTestAppStrings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

class BuildProjectToolTest {
    @Before
    fun setUpStrings() {
        installToolTestAppStrings()
    }

    @After
    fun tearDownStrings() {
        resetToolTestAppStrings()
    }

    @Test
    fun `execute forwards target and clean parameters`(): Unit = runBlocking {
        val callbacks = RecordingExecutionCallbacks()
        val toolCall = ToolCall(
            id = "call-1",
            type = "function",
            function = ToolFunction(
                name = BuildProjectTool.name,
                arguments = """{"target":"app_tests","clean":true}"""
            )
        )

        val result = BuildProjectTool.execute(
            toolCall,
            ToolExecutionContext(additionalData = mapOf("executionCallbacks" to callbacks))
        )

        assertThat(result).isInstanceOf(ToolExecutionResult.Success::class.java)
        assertThat(callbacks.lastBuildRequest).isEqualTo(
            BuildRequest(
                clean = true,
                rebuild = false,
                target = "app_tests"
            )
        )
    }

    @Test
    fun `execute formats successful build output with truncation`(): Unit = runBlocking {
        val callbacks = RecordingExecutionCallbacks(
            executionResult = executionResult(
                success = true,
                status = ExecutionStatus.SUCCESS,
                output = "b".repeat(5001),
                duration = 77
            )
        )

        val result = BuildProjectTool.execute(
            buildToolCall("""{"clean":false,"target":"app"}"""),
            ToolExecutionContext(additionalData = mapOf("executionCallbacks" to callbacks))
        )

        assertThat(result).isInstanceOf(ToolExecutionResult.Success::class.java)
        val success = result as ToolExecutionResult.Success
        assertThat(success.content).contains("Build completed successfully")
        assertThat(success.content).contains("... (output truncated)")
        assertThat(success.metadata).containsExactly(
            "executionId",
            "build-test",
            "exitCode",
            0,
            "duration",
            77L,
            "status",
            "SUCCESS"
        )
    }

    @Test
    fun `execute formats failed and pending builds`() = runBlocking {
        val failedCallbacks = RecordingExecutionCallbacks(
            executionResult = executionResult(
                success = false,
                status = ExecutionStatus.FAILED,
                exitCode = 1,
                errorOutput = "build failed"
            )
        )
        val pendingCallbacks = RecordingExecutionCallbacks(
            executionResult = executionResult(
                success = false,
                status = ExecutionStatus.PENDING,
                output = "queued"
            )
        )

        val failed = BuildProjectTool.execute(
            buildToolCall(),
            ToolExecutionContext(additionalData = mapOf("executionCallbacks" to failedCallbacks))
        )
        val pending = BuildProjectTool.execute(
            buildToolCall(),
            ToolExecutionContext(additionalData = mapOf("executionCallbacks" to pendingCallbacks))
        )

        assertThat(failed).isInstanceOf(ToolExecutionResult.Error::class.java)
        assertThat((failed as ToolExecutionResult.Error).message).contains("build failed")
        assertThat(pending).isInstanceOf(ToolExecutionResult.Error::class.java)
        assertThat((pending as ToolExecutionResult.Error).message).contains("PENDING")
    }

    @Test
    fun `execute returns error when build callbacks are unavailable`(): Unit = runBlocking {
        val result = BuildProjectTool.execute(
            buildToolCall(),
            ToolExecutionContext()
        )

        assertThat(result).isInstanceOf(ToolExecutionResult.Error::class.java)
        assertThat((result as ToolExecutionResult.Error).message).contains("Execution callbacks not available")
    }

    @Test
    fun `execute returns error when default build callback is not implemented`(): Unit = runBlocking {
        val result = BuildProjectTool.execute(
            buildToolCall(),
            ToolExecutionContext(additionalData = mapOf("executionCallbacks" to DefaultExecutionCallbacks()))
        )

        assertThat(result).isInstanceOf(ToolExecutionResult.Error::class.java)
        val message = (result as ToolExecutionResult.Error).message
        assertThat(message).contains("Build failed")
        assertThat(message).contains("Build execution not implemented yet")
        assertThat(message).doesNotContain("Build started")
    }

    private fun buildToolCall(arguments: String = "{}"): ToolCall = ToolCall(
        id = "call-1",
        type = "function",
        function = ToolFunction(
            name = BuildProjectTool.name,
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
        executionId = "build-test",
        success = success,
        exitCode = exitCode,
        output = output,
        errorOutput = errorOutput,
        duration = duration,
        status = status
    )
}
