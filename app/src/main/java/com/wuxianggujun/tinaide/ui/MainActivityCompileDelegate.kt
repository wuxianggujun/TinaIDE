package com.wuxianggujun.tinaide.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.wuxianggujun.tinaide.core.compile.ProcessManager
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * MainActivity 的编译宿主委托。
 *
 * 只负责观察注册和动作转发，保持 MainActivity 的宿主胶水尽量收口。
 */
class MainActivityCompileDelegate(
    private val lifecycleOwner: LifecycleOwner,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val compilerViewModel: CompilerViewModel,
    private val processManager: ProcessManager,
    private val compileActionsHelper: CompileActionsHelper,
    private val compileRuntimeObserver: CompileRuntimeObserver,
    private val compileUiEventObserver: CompileUiEventObserver,
) {
    fun registerObservers() {
        observeCompilerState()
        observeCompileActionsEvents()
    }

    fun onCompileProject() {
        lifecycleScope.launch {
            compileActionsHelper.runProject()
        }
    }

    fun onStopCompile() {
        compilerViewModel.stopRunningProgram()
    }

    fun onRebuildAndRunProject() {
        lifecycleScope.launch {
            compileActionsHelper.rebuildAndRunProject()
        }
    }

    fun onBuildProject() {
        lifecycleScope.launch {
            compileActionsHelper.buildProject()
        }
    }

    fun onDebugProject() {
        lifecycleScope.launch {
            compileActionsHelper.debugProject()
        }
    }

    fun onCompileInTerminal() {
        lifecycleScope.launch {
            compileActionsHelper.runInTerminal()
        }
    }

    fun onCmakeReconfigure() {
        lifecycleScope.launch {
            compileActionsHelper.reconfigureCMake()
        }
    }

    fun onCmakeClearBuildDir() {
        lifecycleScope.launch {
            compileActionsHelper.clearCMakeBuildDirectory()
        }
    }

    fun onCmakeCleanAndReconfigure() {
        lifecycleScope.launch {
            compileActionsHelper.clearAndReconfigureCMake()
        }
    }

    fun onCmakeOpenArtifactsDir() {
        lifecycleScope.launch {
            compileActionsHelper.openCMakeArtifactsDirectory()
        }
    }

    private fun observeCompilerState() {
        lifecycleScope.launch {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(processManager.processState, compilerViewModel.actionGate.isBusy) { processInfo, _ ->
                        processInfo.state
                    }.collect(compileRuntimeObserver::handleProcessStateChanged)
                }

                launch {
                    compilerViewModel.events.collect { event ->
                        compileRuntimeObserver.handleCompileEvent(event)
                    }
                }
            }
        }
    }

    private fun observeCompileActionsEvents() {
        lifecycleScope.launch {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                compileActionsHelper.uiEvents.collect { event ->
                    compileUiEventObserver.handleUiEvent(event)
                }
            }
        }
    }
}
