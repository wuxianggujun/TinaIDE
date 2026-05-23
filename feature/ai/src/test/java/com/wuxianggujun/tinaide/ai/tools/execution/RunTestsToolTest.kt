package com.wuxianggujun.tinaide.ai.tools.execution

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.ai.api.ToolCall
import com.wuxianggujun.tinaide.ai.api.ToolFunction
import com.wuxianggujun.tinaide.ai.tools.ToolExecutionContext
import com.wuxianggujun.tinaide.ai.tools.ToolExecutionResult
import com.wuxianggujun.tinaide.ai.tools.executor.execution.DefaultExecutionCallbacks
import com.wuxianggujun.tinaide.ai.tools.executor.execution.TestRequest
import com.wuxianggujun.tinaide.ai.tools.installToolTestAppStrings
import com.wuxianggujun.tinaide.ai.tools.resetToolTestAppStrings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

class RunTestsToolTest {
    @Before
    fun setUpStrings() {
        installToolTestAppStrings()
    }

    @After
    fun tearDownStrings() {
        resetToolTestAppStrings()
    }

    @Test
    fun `execute forwards json arguments array to test request`(): Unit = runBlocking {
        val callbacks = RecordingExecutionCallbacks()
        val toolCall = ToolCall(
            id = "call-1",
            type = "function",
            function = ToolFunction(
                name = RunTestsTool.name,
                arguments = """{"target":"unit_tests","arguments":["--gtest_filter=Foo.*","--verbose"]}"""
            )
        )

        val result = RunTestsTool.execute(
            toolCall,
            ToolExecutionContext(additionalData = mapOf("executionCallbacks" to callbacks))
        )

        assertThat(result).isInstanceOf(ToolExecutionResult.Success::class.java)
        assertThat(callbacks.lastTestRequest).isEqualTo(
            TestRequest(
                testClass = "unit_tests",
                arguments = listOf("--gtest_filter=Foo.*", "--verbose")
            )
        )
    }

    @Test
    fun `execute prefers explicit test class over target`(): Unit = runBlocking {
        val callbacks = RecordingExecutionCallbacks()
        val toolCall = ToolCall(
            id = "call-1",
            type = "function",
            function = ToolFunction(
                name = RunTestsTool.name,
                arguments = """{"target":"unit_tests","test_class":"integration_tests"}"""
            )
        )

        RunTestsTool.execute(
            toolCall,
            ToolExecutionContext(additionalData = mapOf("executionCallbacks" to callbacks))
        )

        assertThat(callbacks.lastTestRequest?.testClass).isEqualTo("integration_tests")
    }

    @Test
    fun `execute returns error when default test callback is not implemented`(): Unit = runBlocking {
        val result = RunTestsTool.execute(
            runTestsToolCall(),
            ToolExecutionContext(additionalData = mapOf("executionCallbacks" to DefaultExecutionCallbacks()))
        )

        assertThat(result).isInstanceOf(ToolExecutionResult.Error::class.java)
        val message = (result as ToolExecutionResult.Error).message
        assertThat(message).contains("Tests failed")
        assertThat(message).contains("Test execution not implemented yet")
        assertThat(message).doesNotContain("Test execution started")
    }

    @Test
    fun `execute returns error when test callbacks are unavailable`(): Unit = runBlocking {
        val result = RunTestsTool.execute(
            runTestsToolCall(),
            ToolExecutionContext()
        )

        assertThat(result).isInstanceOf(ToolExecutionResult.Error::class.java)
        assertThat((result as ToolExecutionResult.Error).message).contains("Execution callbacks not available")
    }

    private fun runTestsToolCall(arguments: String = "{}"): ToolCall = ToolCall(
        id = "call-1",
        type = "function",
        function = ToolFunction(
            name = RunTestsTool.name,
            arguments = arguments
        )
    )
}
