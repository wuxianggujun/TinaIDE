package com.wuxianggujun.tinaide

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.wuxianggujun.tinaide.core.compile.ProcessManager
import com.wuxianggujun.tinaide.core.config.IConfigManager
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.editor.IEditorManager
import com.wuxianggujun.tinaide.extensions.toastError
import com.wuxianggujun.tinaide.extensions.toastInfo
import com.wuxianggujun.tinaide.extensions.toastSuccess
import com.wuxianggujun.tinaide.file.IProjectContext
import com.wuxianggujun.tinaide.file.IProjectSession
import com.wuxianggujun.tinaide.output.IOutputManager
import com.wuxianggujun.tinaide.startup.StartupFlowManager
import com.wuxianggujun.tinaide.ui.BottomPanelViewModel
import com.wuxianggujun.tinaide.ui.CompilerViewModel
import com.wuxianggujun.tinaide.ui.DebugViewModel
import com.wuxianggujun.tinaide.ui.EditorStateViewModel
import com.wuxianggujun.tinaide.ui.GitViewModel
import com.wuxianggujun.tinaide.ui.MainActivityActionsViewModel
import com.wuxianggujun.tinaide.ui.MainActivityBottomPanelActionBridge
import com.wuxianggujun.tinaide.ui.MainActivityCompileHost
import com.wuxianggujun.tinaide.ui.MainActivityEditorActionBridge
import com.wuxianggujun.tinaide.ui.MainActivityExternalFileLauncher
import com.wuxianggujun.tinaide.ui.MainActivityFileTreeActionBridge
import com.wuxianggujun.tinaide.ui.MainActivityNavigationHost
import com.wuxianggujun.tinaide.ui.MainActivityShortcutDispatcher
import com.wuxianggujun.tinaide.ui.MainViewModel
import com.wuxianggujun.tinaide.ui.MainPortalActivity
import com.wuxianggujun.tinaide.ui.TinaComponentActivity
import com.wuxianggujun.tinaide.ui.compose.screens.main.MainActivityContentBridges
import com.wuxianggujun.tinaide.ui.compose.screens.main.MainActivityContentDelegates
import com.wuxianggujun.tinaide.ui.compose.screens.main.MainActivityContentServices
import com.wuxianggujun.tinaide.ui.compose.screens.main.MainActivityContentViewModels
import com.wuxianggujun.tinaide.ui.compose.screens.main.MainActivityExternalFileActions
import com.wuxianggujun.tinaide.ui.compose.screens.main.installMainActivityContent
import com.wuxianggujun.tinaide.ui.createMainActivityActionsDelegate
import com.wuxianggujun.tinaide.ui.createMainActivityCompileHost
import com.wuxianggujun.tinaide.ui.createMainActivityNavigationHost
import com.wuxianggujun.tinaide.ui.createMainActivityWorkspaceHost
import com.wuxianggujun.tinaide.ui.installMainActivityCleanup
import com.wuxianggujun.tinaide.ui.installMainActivityStartup
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.viewmodel.ext.android.viewModel as koinViewModel
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 主界面 Activity
 *
 * 纯 Compose 实现:
 * - FileTree: 纯 Compose LazyColumn 实现
 * - EditorContainer: Compose + AndroidView(CodeEditor)
 * - BottomPanel: 纯 Compose 实现
 */
class MainActivity :
    TinaComponentActivity(),
    MainActivityExternalFileLauncher,
    KoinComponent {

    private val projectContext: IProjectContext by inject()
    private val projectSession: IProjectSession by inject()
    private val configManager: IConfigManager by inject()
    private val editorManager: IEditorManager by inject()
    private val processManager: ProcessManager by inject()
    private val outputManager: IOutputManager by inject()

    private val compilerViewModel: CompilerViewModel by koinViewModel()
    private val bottomPanelViewModel: BottomPanelViewModel by koinViewModel()
    private val editorStateViewModel: EditorStateViewModel by koinViewModel()
    private val mainViewModel: MainViewModel by koinViewModel()
    private val debugViewModel: DebugViewModel by koinViewModel()
    private val gitViewModel: GitViewModel by koinViewModel()
    private val actionsViewModel: MainActivityActionsViewModel by koinViewModel()
    private val actionsDelegate by lazy(LazyThreadSafetyMode.NONE) {
        createMainActivityActionsDelegate(
            activity = this,
            actionsViewModel = actionsViewModel,
            onToastSuccess = ::toastSuccess,
            onToastError = ::toastError,
            onToastInfo = ::toastInfo,
        )
    }

    private val editorActionBridge = MainActivityEditorActionBridge()
    private val fileTreeActionBridge = MainActivityFileTreeActionBridge()
    private val bottomPanelController = MainActivityBottomPanelActionBridge()
    private val shortcutDispatcher = MainActivityShortcutDispatcher()
    private val compileHost: MainActivityCompileHost by lazy(LazyThreadSafetyMode.NONE) {
        createMainActivityCompileHost(
            activity = this,
            compilerViewModel = compilerViewModel,
            mainViewModel = mainViewModel,
            bottomPanelViewModel = bottomPanelViewModel,
            debugViewModel = debugViewModel,
            bottomPanelController = bottomPanelController,
            projectContext = projectContext,
            editorManager = editorManager,
            processManager = processManager,
            fileTreeActionBridge = fileTreeActionBridge,
            onToastSuccess = ::toastSuccess,
            onToastError = ::toastError,
            onToastInfo = ::toastInfo,
        )
    }
    private val workspaceHost by lazy(LazyThreadSafetyMode.NONE) {
        createMainActivityWorkspaceHost(
            activity = this,
            projectSession = projectSession,
            projectContext = projectContext,
            editorManager = editorManager,
            gitViewModel = gitViewModel,
            bottomPanelViewModel = bottomPanelViewModel,
            bottomPanelController = bottomPanelController,
            onToastSuccess = ::toastSuccess,
            onToastError = ::toastError,
            onToastInfo = ::toastInfo,
        )
    }
    private val navigationHost: MainActivityNavigationHost by lazy(LazyThreadSafetyMode.NONE) {
        createMainActivityNavigationHost(
            activity = this,
            projectContext = projectContext,
            editorActionBridge = editorActionBridge,
            bottomPanelController = bottomPanelController,
            onToastError = ::toastError,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        var workspaceStartupPending = true
        splashScreen.setKeepOnScreenCondition { workspaceStartupPending }
        Prefs.applyTheme()
        super.onCreate(savedInstanceState)

        // Activity Result launchers必须在Activity进入STARTED之前注册。
        // 启动检查可以异步执行，但导航宿主必须在同步onCreate阶段完成创建。
        val currentNavigationHost = navigationHost
        lifecycleScope.launch {
            val startupResult = withContext(Dispatchers.IO) {
                val redirect = StartupFlowManager(this@MainActivity, configManager).checkStartupFlow()
                val project = if (redirect == null) projectSession.restoreLastSession() else null
                redirect to project
            }
            val redirectIntent = startupResult.first
            if (redirectIntent != null) {
                workspaceStartupPending = false
                startActivity(redirectIntent)
                finish()
                return@launch
            }
            if (startupResult.second == null) {
                workspaceStartupPending = false
                startActivity(
                    Intent(this@MainActivity, MainPortalActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                )
                finish()
                return@launch
            }

            installWorkspaceContent(currentNavigationHost)
            workspaceStartupPending = false
        }
    }

    private fun installWorkspaceContent(currentNavigationHost: MainActivityNavigationHost) {
        val currentCompileHost = compileHost
        val currentWorkspaceHost = workspaceHost
        installMainActivityStartup(
            activity = this,
            actionsDelegate = actionsDelegate,
            compileDelegate = currentCompileHost.compileDelegate,
        ) {
            installMainActivityContent(
                activity = this,
                viewModels = MainActivityContentViewModels(
                    compiler = compilerViewModel,
                    main = mainViewModel,
                    editorState = editorStateViewModel,
                    debug = debugViewModel,
                    git = gitViewModel,
                    bottomPanel = bottomPanelViewModel,
                    actions = actionsViewModel,
                ),
                services = MainActivityContentServices(
                    projectContext = projectContext,
                    editorManager = editorManager,
                    processManager = processManager,
                    outputManager = outputManager,
                ),
                bridges = MainActivityContentBridges(
                    fileTreeActions = fileTreeActionBridge,
                    bottomPanelActions = bottomPanelController,
                    editorActions = editorActionBridge,
                ),
                delegates = MainActivityContentDelegates(
                    actions = actionsDelegate,
                    compileActionsHelper = currentCompileHost.compileActionsHelper,
                    compile = currentCompileHost.compileDelegate,
                    navigation = currentNavigationHost.navigationDelegate,
                    shortcuts = shortcutDispatcher,
                    dialogCoordinator = currentWorkspaceHost.dialogCoordinator,
                    workspaceActions = currentWorkspaceHost.workspaceActions,
                ),
                externalFileActions = MainActivityExternalFileActions(
                    openWithExternalApp = ::openWithExternalApp,
                    shareFileOrDirectory = ::shareFileOrDirectory,
                ),
            )
        }
    }

    override fun openWithExternalApp(file: File) {
        workspaceHost.externalFileLauncher.openWithExternalApp(file)
    }

    override fun shareFileOrDirectory(file: File) {
        workspaceHost.externalFileLauncher.shareFileOrDirectory(file)
    }

    /**
     * 硬件键盘快捷键处理
     *
     * 支持的快捷键可在设置中自定义，默认：
     * - Ctrl+S: 保存当前文件
     * - Ctrl+Shift+S: 保存全部文件
     * - Ctrl+W: 关闭当前标签页
     * - Ctrl+Shift+W: 关闭全部标签页
     * - Ctrl+Z: 撤销
     * - Ctrl+Shift+Z / Ctrl+Y: 重做
     * - Ctrl+Tab: 切换到下一个标签页
     * - Ctrl+Shift+Tab: 切换到上一个标签页
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (shortcutDispatcher.dispatch(event)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        installMainActivityCleanup(
            context = this,
            bottomPanelController = bottomPanelController,
            fileTreeActionBridge = fileTreeActionBridge,
            editorActionBridge = editorActionBridge,
            shortcutDispatcher = shortcutDispatcher,
        )
        super.onDestroy()
    }
}
