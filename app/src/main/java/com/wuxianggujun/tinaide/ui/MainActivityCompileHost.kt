package com.wuxianggujun.tinaide.ui

import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.wuxianggujun.tinaide.core.compile.ProcessManager
import com.wuxianggujun.tinaide.editor.IEditorManager
import com.wuxianggujun.tinaide.file.IProjectContext
import java.io.File

/**
 * 收口 MainActivity 的编译宿主装配，避免 Activity 直接堆叠多段关联初始化。
 */
internal class MainActivityCompileHost(
    val compileActionsHelper: CompileActionsHelper,
    val compileDelegate: MainActivityCompileDelegate,
)

internal fun createMainActivityCompileHost(
    activity: ComponentActivity,
    compilerViewModel: CompilerViewModel,
    mainViewModel: MainViewModel,
    bottomPanelViewModel: BottomPanelViewModel,
    debugViewModel: DebugViewModel,
    bottomPanelController: MainActivityBottomPanelActionBridge,
    projectContext: IProjectContext,
    editorManager: IEditorManager,
    processManager: ProcessManager,
    fileTreeActionBridge: MainActivityFileTreeActionBridge,
    onToastSuccess: (String) -> Unit,
    onToastError: (String) -> Unit,
    onToastInfo: (String) -> Unit,
): MainActivityCompileHost {
    val compileActionsHelper = CompileActionsHelper(
        context = activity.applicationContext,
        commandRunner = CompilerViewModelCommandRunner(compilerViewModel),
        uiBridge = ViewModelCompileUiBridge(
            mainViewModel = mainViewModel,
            bottomPanelViewModel = bottomPanelViewModel,
            debugViewModel = debugViewModel,
            bottomPanelController = bottomPanelController,
        ),
        projectContext = projectContext,
        editorManager = editorManager,
        processController = ProcessManagerCompileProcessController(processManager),
    )
    val compileRuntimeObserver = CompileRuntimeObserver(
        compileActionsHelper = compileActionsHelper,
        fileTreeSynchronizer = object : CompileRuntimeObserver.FileTreeSynchronizer {
            override suspend fun refreshAndRevealExportedArtifact(exportedArtifactPath: String?) {
                fileTreeActionBridge.refreshAndRevealExportedArtifact(exportedArtifactPath)
            }
        },
    )
    val compileUiEventObserver = CompileUiEventObserver(
        toastPresenter = LambdaCompileToastPresenter(
            onSuccess = onToastSuccess,
            onError = onToastError,
            onInfo = onToastInfo,
        ),
        graphicalRuntimeLauncher = ContextCompileGraphicalRuntimeLauncher(
            sdlLauncher = ContextCompileSdlLauncher(
                context = activity,
                onError = onToastError,
                activityStarter = activity::startActivity,
            ),
            nativeActivityLauncher = ContextCompileNativeActivityLauncher(
                context = activity,
                onError = onToastError,
                activityStarter = activity::startActivity,
            ),
        ),
        terminalLauncher = ContextCompileTerminalLauncher(
            context = activity,
            activityStarter = activity::startActivity,
        ),
        projectTreeRevealer = object : CompileUiEventObserver.ProjectTreeRevealer {
            override suspend fun reveal(file: File, selectTarget: Boolean) {
                fileTreeActionBridge.reveal(file, selectTarget)
            }
        },
    )
    val compileDelegate = MainActivityCompileDelegate(
        lifecycleOwner = activity,
        lifecycleScope = activity.lifecycleScope,
        compilerViewModel = compilerViewModel,
        processManager = processManager,
        compileActionsHelper = compileActionsHelper,
        compileRuntimeObserver = compileRuntimeObserver,
        compileUiEventObserver = compileUiEventObserver,
    )

    return MainActivityCompileHost(
        compileActionsHelper = compileActionsHelper,
        compileDelegate = compileDelegate,
    )
}
