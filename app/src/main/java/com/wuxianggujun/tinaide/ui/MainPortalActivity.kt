package com.wuxianggujun.tinaide.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.wuxianggujun.tinaide.MainActivity
import com.wuxianggujun.tinaide.core.config.IConfigManager
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.extensions.toastError
import com.wuxianggujun.tinaide.file.IProjectSession
import com.wuxianggujun.tinaide.settings.SettingsActivity
import com.wuxianggujun.tinaide.startup.StartupFlowManager
import com.wuxianggujun.tinaide.ui.compose.screens.main.MainScreen
import com.wuxianggujun.tinaide.ui.compose.screens.settings.SettingsRoute
import com.wuxianggujun.tinaide.ui.theme.TinaIDETheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * 主入口 Activity
 *
 * 管理底部导航和四个主要模块的切换：
 * - 项目：项目管理、项目列表
 * - 市场：插件市场、包管理、代码片段
 * - 教程：教程列表、学习进度
 * - 我的：用户信息、设置、我的内容
 *
 * 当用户打开项目后，跳转到 MainActivity 进行编辑
 */
class MainPortalActivity :
    TinaComponentActivity(),
    KoinComponent {

    private var sessionCleanupJob: Job? = null
    private var projectOpenJob: Job? = null

    override fun onStart() {
        super.onStart()
        // 用户一旦回到主页即归零项目会话内存态（FileWatcher 同步移除），
        // 保证从主页进入的设置页/插件/工作区等拿到 null，而不是被上次会话污染。
        // 偏好键 ConfigKeys.CurrentProject 保留，进入 MainActivity 时再通过
        // projectSession.restoreLastSession() 恢复。
        val projectSession: IProjectSession = get()
        sessionCleanupJob = lifecycleScope.launch(Dispatchers.IO) {
            projectSession.clearInMemorySession()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        var startupCheckPending = true
        splashScreen.setKeepOnScreenCondition { startupCheckPending }
        Prefs.applyTheme()
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        enableEdgeToEdge()

        // === 启动流程检查 ===
        // 检查是否需要引导（工具链安装/工作空间配置），如需要则跳转到对应页面并结束当前 Activity
        val configManager: IConfigManager = get()
        lifecycleScope.launch {
            val redirectIntent = withContext(Dispatchers.IO) {
                StartupFlowManager(this@MainPortalActivity, configManager).checkStartupFlow()
            }
            if (redirectIntent != null) {
                startupCheckPending = false
                startActivity(redirectIntent)
                finish()
                return@launch
            }

            installPortalContent()
            startupCheckPending = false
        }
    }

    private fun installPortalContent() {
        setContent {
            TinaIDETheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainPortalScreen(
                        onOpenProject = ::openProjectEditor,
                        onNavigateToSettings = { SettingsActivity.start(this) },
                        onNavigateToAbout = { SettingsActivity.start(this, SettingsRoute.About) },
                        onNavigateToPlugins = { SettingsActivity.start(this, SettingsRoute.Plugins) },
                        onNavigateToPackages = { SettingsActivity.start(this, SettingsRoute.Packages) },
                        onNavigateToFavorites = {
                            startActivity(
                                Intent(this, UserContentActivity::class.java).apply {
                                    putExtra(UserContentActivity.EXTRA_CONTENT_TYPE, UserContentActivity.TYPE_FAVORITES)
                                }
                            )
                        },
                        onNavigateToDownloadHistory = {
                            startActivity(
                                Intent(this, UserContentActivity::class.java).apply {
                                    putExtra(UserContentActivity.EXTRA_CONTENT_TYPE, UserContentActivity.TYPE_DOWNLOAD_HISTORY)
                                }
                            )
                        }
                    )
                }
            }
        }
    }

    private fun openProjectEditor(projectPath: String) {
        if (projectPath.isBlank() || projectOpenJob?.isActive == true) return
        val projectSession: IProjectSession = get()
        projectOpenJob = lifecycleScope.launch {
            sessionCleanupJob?.join()
            val result = withContext(Dispatchers.IO) {
                runCatching { projectSession.openProject(File(projectPath).absolutePath) }
            }
            result.onSuccess {
                startActivity(Intent(this@MainPortalActivity, MainActivity::class.java))
            }.onFailure { error ->
                toastError(error.message ?: Strings.toast_open_failed.strOr(this@MainPortalActivity))
            }
        }
    }
}

/**
 * 主入口屏幕
 *
 * 包含底部导航和模块切换
 */
@Composable
fun MainPortalScreen(
    onOpenProject: (String) -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
    onNavigateToPlugins: () -> Unit = {},
    onNavigateToPackages: () -> Unit = {},
    onNavigateToFavorites: () -> Unit = {},
    onNavigateToDownloadHistory: () -> Unit = {},
) {
    MainScreen(
        onOpenProject = onOpenProject,
        onNavigateToSettings = onNavigateToSettings,
        onNavigateToAbout = onNavigateToAbout,
        onNavigateToPlugins = onNavigateToPlugins,
        onNavigateToPackages = onNavigateToPackages,
        onNavigateToFavorites = onNavigateToFavorites,
        onNavigateToDownloadHistory = onNavigateToDownloadHistory,
    )
}
