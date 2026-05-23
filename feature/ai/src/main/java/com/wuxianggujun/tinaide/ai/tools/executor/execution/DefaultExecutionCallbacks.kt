package com.wuxianggujun.tinaide.ai.tools.executor.execution

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 默认的执行回调实现
 * 提供基础的执行功能实现
 */
class DefaultExecutionCallbacks : ExecutionCallbacks {

    private val executionStatusMap = ConcurrentHashMap<String, ExecutionStatus>()
    private val executionResultMap = ConcurrentHashMap<String, ExecutionResult>()

    override fun runProject(request: RunRequest): ExecutionResult {
        val executionId = UUID.randomUUID().toString()

        val result = ExecutionResult(
            executionId = executionId,
            success = false,
            exitCode = -1,
            output = "",
            errorOutput = "Project execution not implemented yet",
            duration = 0,
            status = ExecutionStatus.FAILED
        )
        executionStatusMap[executionId] = result.status
        executionResultMap[executionId] = result
        return result
    }

    override fun runTests(request: TestRequest): ExecutionResult {
        val executionId = UUID.randomUUID().toString()

        val result = ExecutionResult(
            executionId = executionId,
            success = false,
            exitCode = -1,
            output = "",
            errorOutput = "Test execution not implemented yet",
            duration = 0,
            status = ExecutionStatus.FAILED
        )
        executionStatusMap[executionId] = result.status
        executionResultMap[executionId] = result
        return result
    }

    override fun buildProject(request: BuildRequest): ExecutionResult {
        val executionId = UUID.randomUUID().toString()

        val result = ExecutionResult(
            executionId = executionId,
            success = false,
            exitCode = -1,
            output = "",
            errorOutput = "Build execution not implemented yet",
            duration = 0,
            status = ExecutionStatus.FAILED
        )
        executionStatusMap[executionId] = result.status
        executionResultMap[executionId] = result
        return result
    }

    override fun stopExecution(executionId: String): Boolean {
        val status = executionStatusMap[executionId]
        if (status == ExecutionStatus.RUNNING || status == ExecutionStatus.PENDING) {
            executionStatusMap[executionId] = ExecutionStatus.CANCELLED
            return true
        }
        return false
    }

    override fun getExecutionStatus(executionId: String): ExecutionStatus? = executionStatusMap[executionId]

    override fun getExecutionOutput(executionId: String): ExecutionOutputResult? {
        val result = executionResultMap[executionId] ?: return null
        return ExecutionOutputResult(
            executionId = result.executionId,
            output = result.output,
            errorOutput = result.errorOutput,
            status = result.status,
            exitCode = result.exitCode
        )
    }

    override fun getBuildErrors(executionId: String?): BuildErrorsResult {
        // 默认实现返回空错误列表
        return BuildErrorsResult(
            hasErrors = false,
            errorCount = 0,
            warningCount = 0,
            errors = emptyList()
        )
    }

    override fun navigateToRunOutput() {
        // 默认实现为空，子类可以覆盖
    }

    override fun navigateToBuildLog() {
        // 默认实现为空，子类可以覆盖
    }

    /**
     * 更新执行状态
     */
    fun updateExecutionStatus(executionId: String, status: ExecutionStatus) {
        executionStatusMap[executionId] = status
    }

    /**
     * 清除执行状态
     */
    fun clearExecutionStatus(executionId: String) {
        executionStatusMap.remove(executionId)
        executionResultMap.remove(executionId)
    }
}
