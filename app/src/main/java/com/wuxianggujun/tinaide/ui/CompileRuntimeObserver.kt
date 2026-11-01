package com.wuxianggujun.tinaide.ui

import com.wuxianggujun.tinaide.core.compile.ProcessManager

/**
 * 编译运行时观察器
 *
 * 负责把进程状态、编译事件转换成 CompileActionsHelper 可消费的调用，
 * 并在构建成功后同步文件树。
 */
class CompileRuntimeObserver(
    private val compileActionsHelper: CompileActionsHelper,
    private val fileTreeSynchronizer: FileTreeSynchronizer,
) {
    interface FileTreeSynchronizer {
        suspend fun refreshAndRevealExportedArtifact(exportedArtifactPath: String?)
    }

    suspend fun handleProcessStateChanged(processState: ProcessManager.ProcessState) {
        compileActionsHelper.handleProcessStateChanged(processState.toExecutionProcessState())
    }

    suspend fun handleCompileEvent(event: CompileEvent) {
        when (event) {
            is CompileEvent.Success -> {
                if (compileActionsHelper.handleCompileSuccess(event)) {
                    fileTreeSynchronizer.refreshAndRevealExportedArtifact(
                        event.report.artifact?.exportedPath
                    )
                }
            }

            is CompileEvent.Error -> compileActionsHelper.handleCompileError(event)
        }
    }

    private fun ProcessManager.ProcessState.toExecutionProcessState(): CompileActionsHelper.ExecutionProcessState = when (this) {
        ProcessManager.ProcessState.IDLE -> CompileActionsHelper.ExecutionProcessState.IDLE
        ProcessManager.ProcessState.STARTING -> CompileActionsHelper.ExecutionProcessState.STARTING
        ProcessManager.ProcessState.RUNNING -> CompileActionsHelper.ExecutionProcessState.RUNNING
        ProcessManager.ProcessState.STOPPING -> CompileActionsHelper.ExecutionProcessState.STOPPING
        ProcessManager.ProcessState.STOPPED -> CompileActionsHelper.ExecutionProcessState.STOPPED
    }
}
