package com.wuxianggujun.tinaide.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.gyf.immersionbar.ktx.immersionBar
import com.wuxianggujun.tinaide.core.config.IConfigManager
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.packages.PackageManagerNavigation
import com.wuxianggujun.tinaide.plugin.EditorThemeIndex
import com.wuxianggujun.tinaide.plugin.PluginHostLogSources
import com.wuxianggujun.tinaide.plugin.PluginLogManager
import com.wuxianggujun.tinaide.plugin.PluginManager
import com.wuxianggujun.tinaide.plugin.lsp.LspPluginManager
import com.wuxianggujun.tinaide.ui.compose.screens.help.HelpScreen
import com.wuxianggujun.tinaide.ui.compose.screens.help.HelpViewModel
import com.wuxianggujun.tinaide.ui.compose.screens.packages.PackageManagerScreen
import com.wuxianggujun.tinaide.ui.compose.screens.settings.SettingsRoute
import com.wuxianggujun.tinaide.ui.compose.screens.settings.SettingsScreen
import com.wuxianggujun.tinaide.ui.compose.screens.settings.SettingsViewModel
import com.wuxianggujun.tinaide.ui.theme.TinaIDETheme
import com.wuxianggujun.tinaide.ui.wizard.NewProjectWizardActivity
import com.wuxianggujun.tinaide.ui.workspace.DependencyInstallActivity
import com.wuxianggujun.tinaide.ui.workspace.PRootLogActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rerere.rikkahub.RikkaHubEmbeddedSettingsPane
import me.rerere.rikkahub.RikkaHubEmbeddedWarmup
import org.koin.androidx.viewmodel.ext.android.viewModel as koinViewModel
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import timber.log.Timber

internal object SettingsActivitySupport {
    private val initialRoutes = listOf(
        SettingsRoute.Root,
        SettingsRoute.Editor,
        SettingsRoute.Lsp,
        SettingsRoute.Compiler,
        SettingsRoute.Project,
        SettingsRoute.Storage,
        SettingsRoute.StorageCleanup,
        SettingsRoute.Terminal,
        SettingsRoute.Ai,
        SettingsRoute.Git,
        SettingsRoute.Appearance,
        SettingsRoute.Keyboard,
        SettingsRoute.Plugins,
        SettingsRoute.Packages,
        SettingsRoute.PluginMarketplace,
        SettingsRoute.PluginLog,
        SettingsRoute.Help,
        SettingsRoute.Developer,
        SettingsRoute.About
    ).associateBy(SettingsRoute::route)

    fun buildStartIntent(
        context: Context,
        initialRoute: SettingsRoute? = null,
        initialHelpDocumentId: String? = null,
        initialPluginDetailId: String? = null,
        initialPackageSearchQuery: String? = null,
    ): Intent = Intent(context, SettingsActivity::class.java).apply {
        if (context !is Activity) {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        initialRoute?.let { putExtra(SettingsActivity.EXTRA_INITIAL_ROUTE, it.route) }
        initialHelpDocumentId
            ?.takeUnless { it.isBlank() }
            ?.let { putExtra(SettingsActivity.EXTRA_INITIAL_HELP_DOCUMENT_ID, it) }
        initialPluginDetailId
            ?.takeUnless { it.isBlank() }
            ?.let { putExtra(SettingsActivity.EXTRA_INITIAL_PLUGIN_DETAIL_ID, it) }
        initialPackageSearchQuery
            ?.takeUnless { it.isBlank() }
            ?.let { putExtra(SettingsActivity.EXTRA_INITIAL_PACKAGE_SEARCH_QUERY, it) }
    }

    fun extractInitialRouteId(intent: Intent): String? = intent.getStringExtra(SettingsActivity.EXTRA_INITIAL_ROUTE)
        ?.takeUnless { it.isBlank() }

    fun extractInitialHelpDocumentId(intent: Intent): String? = intent.getStringExtra(SettingsActivity.EXTRA_INITIAL_HELP_DOCUMENT_ID)
        ?.takeUnless { it.isBlank() }

    fun extractInitialPluginDetailId(intent: Intent): String? = intent.getStringExtra(SettingsActivity.EXTRA_INITIAL_PLUGIN_DETAIL_ID)
        ?.takeUnless { it.isBlank() }

    fun extractInitialPackageSearchQuery(intent: Intent): String? = intent.getStringExtra(
        SettingsActivity.EXTRA_INITIAL_PACKAGE_SEARCH_QUERY
    )?.takeUnless { it.isBlank() }
        ?: PackageManagerNavigation.extractInitialSearchQuery(intent)

    fun resolveInitialRoute(routeId: String?): SettingsRoute = routeId?.takeUnless { it.isBlank() }?.let(initialRoutes::get) ?: SettingsRoute.Root

    fun resolveInitialRoute(intent: Intent): SettingsRoute = if (
        PackageManagerNavigation.isPackageManagerIntent(intent)
    ) {
        SettingsRoute.Packages
    } else {
        resolveInitialRoute(extractInitialRouteId(intent))
    }
}

internal data class SettingsNavigationState(
    val currentRoute: SettingsRoute,
    val routeStack: List<SettingsRoute>
)

internal data class SettingsBackNavigationResult(
    val currentRoute: SettingsRoute? = null,
    val routeStack: List<SettingsRoute>,
    val shouldFinish: Boolean
)

internal object SettingsActivityNavigationSupport {
    fun navigateTo(
        currentRoute: SettingsRoute,
        routeStack: List<SettingsRoute>,
        targetRoute: SettingsRoute
    ): SettingsNavigationState = SettingsNavigationState(
        currentRoute = targetRoute,
        routeStack = routeStack + currentRoute
    )

    fun navigateBack(routeStack: List<SettingsRoute>): SettingsBackNavigationResult {
        if (routeStack.isEmpty()) {
            return SettingsBackNavigationResult(
                routeStack = emptyList(),
                shouldFinish = true
            )
        }

        return SettingsBackNavigationResult(
            currentRoute = routeStack.last(),
            routeStack = routeStack.dropLast(1),
            shouldFinish = false
        )
    }
}

/**
 * 设置界面 Activity（纯 Compose 实现）。
 *
 * 使用 Compose 导航管理设置页面的层级结构。
 */
class SettingsActivity :
    ComponentActivity(),
    KoinComponent {

    companion object {
        private const val TAG = "SettingsActivity"
        const val EXTRA_INITIAL_ROUTE = "extra_initial_route"
        const val EXTRA_INITIAL_HELP_DOCUMENT_ID = "extra_initial_help_document_id"
        const val EXTRA_INITIAL_PLUGIN_DETAIL_ID = "extra_initial_plugin_detail_id"
        const val EXTRA_INITIAL_PACKAGE_SEARCH_QUERY = "extra_initial_package_search_query"
        const val EXTRA_PROJECT_ROOT = "extra_project_root"

        fun start(
            context: Context,
            initialRoute: SettingsRoute? = null,
            initialHelpDocumentId: String? = null,
        ) {
            context.startActivity(
                SettingsActivitySupport.buildStartIntent(
                    context = context,
                    initialRoute = initialRoute,
                    initialHelpDocumentId = initialHelpDocumentId,
                )
            )
        }

        fun startPluginDetail(context: Context, pluginId: String) {
            context.startActivity(
                SettingsActivitySupport.buildStartIntent(
                    context = context,
                    initialRoute = SettingsRoute.Plugins,
                    initialPluginDetailId = pluginId,
                )
            )
        }

        fun startPackages(context: Context, searchQuery: String? = null) {
            context.startActivity(
                SettingsActivitySupport.buildStartIntent(
                    context = context,
                    initialRoute = SettingsRoute.Packages,
                    initialPackageSearchQuery = searchQuery,
                )
            )
        }

        /**
         * 从项目列表菜单进入"指定项目"的项目设置页。
         *
         * 不会触发 IProjectSession.openProject —— 目标项目的元数据通过
         * [EXTRA_PROJECT_ROOT] 传给 SettingsViewModel，直接读写 ProjectMetadataStore。
         * 原会话项目（若有）不受影响。
         */
        fun startForProject(context: Context, projectRootPath: String) {
            val intent = SettingsActivitySupport
                .buildStartIntent(context, SettingsRoute.Project)
                .putExtra(EXTRA_PROJECT_ROOT, projectRootPath)
            context.startActivity(intent)
        }
    }

    private val settingsViewModel: SettingsViewModel by koinViewModel()
    private val helpViewModel: HelpViewModel by koinViewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        Prefs.applyTheme() // 应用系统级主题设置
        super.onCreate(savedInstanceState)

        // 设置沉浸式状态栏（与 MainPortalActivity 保持一致）
        immersionBar {
            transparentStatusBar()
            statusBarDarkFont(!Prefs.useDarkMode)
            navigationBarColor(android.R.color.transparent)
            navigationBarDarkIcon(!Prefs.useDarkMode)
        }
        warmUpRikkaHubSettings()

        // 若从项目列表菜单进入，绑定目标项目根路径；否则走默认（使用当前会话项目）
        val targetProjectRoot = intent.getStringExtra(EXTRA_PROJECT_ROOT)?.takeUnless { it.isBlank() }
        val initialHelpDocumentId = SettingsActivitySupport.extractInitialHelpDocumentId(intent)
        val initialPluginDetailId = SettingsActivitySupport.extractInitialPluginDetailId(intent)
        val initialPackageSearchQuery = SettingsActivitySupport.extractInitialPackageSearchQuery(intent)
        settingsViewModel.setTargetProjectRoot(targetProjectRoot)
        initialHelpDocumentId?.let(helpViewModel::selectDocumentById)

        val configManager: IConfigManager = get()
        val pluginLogManager = PluginLogManager.getInstance(applicationContext)
        val pluginManager = PluginManager.getInstance(applicationContext)
        Timber.tag(TAG).i(
            "Using PluginManager instance=%s",
            pluginManager.instanceId
        )
        pluginLogManager.info(
            PluginHostLogSources.Settings,
            "SettingsActivity using PluginManager instance=${pluginManager.instanceId}"
        )
        val themeRegistry: EditorThemeIndex = get()
        val lspPluginManager: LspPluginManager? = getKoin().getOrNull()
        setContent {
            TinaIDETheme {
                val initialRoute = SettingsActivitySupport.resolveInitialRoute(intent)
                var currentRoute by remember { mutableStateOf<SettingsRoute>(initialRoute) }
                val routeStack = remember { mutableStateListOf<SettingsRoute>() }

                // 处理系统返回手势
                BackHandler(enabled = routeStack.isNotEmpty()) {
                    val backResult = SettingsActivityNavigationSupport.navigateBack(routeStack)
                    routeStack.clear()
                    routeStack.addAll(backResult.routeStack)
                    backResult.currentRoute?.let { currentRoute = it }
                }

                SettingsScreen(
                    currentRoute = currentRoute,
                    settingsViewModel = settingsViewModel,
                    pluginManager = pluginManager,
                    themeRegistry = themeRegistry,
                    lspPluginManager = lspPluginManager,
                    initialPluginIdForDetail = initialPluginDetailId,
                    onNavigateBack = {
                        val backResult = SettingsActivityNavigationSupport.navigateBack(routeStack)
                        routeStack.clear()
                        routeStack.addAll(backResult.routeStack)
                        if (backResult.shouldFinish) {
                            finish()
                        } else {
                            backResult.currentRoute?.let { currentRoute = it }
                        }
                    },
                    onNavigateTo = { route ->
                        val nextState = SettingsActivityNavigationSupport.navigateTo(
                            currentRoute = currentRoute,
                            routeStack = routeStack,
                            targetRoute = route
                        )
                        routeStack.clear()
                        routeStack.addAll(nextState.routeStack)
                        currentRoute = nextState.currentRoute
                    },
                    helpContent = { onBack ->
                        HelpScreen(
                            viewModel = helpViewModel,
                            onNavigateBack = onBack,
                            onCreatePluginProject = {
                                startActivity(
                                    NewProjectWizardActivity.createPluginProjectIntent(this@SettingsActivity)
                                )
                            },
                            onOpenPluginSettings = {
                                val nextState = SettingsActivityNavigationSupport.navigateTo(
                                    currentRoute = currentRoute,
                                    routeStack = routeStack,
                                    targetRoute = SettingsRoute.Plugins,
                                )
                                routeStack.clear()
                                routeStack.addAll(nextState.routeStack)
                                currentRoute = nextState.currentRoute
                            },
                        )
                    },
                    packagesContent = { onBack ->
                        PackageManagerScreen(
                            onNavigateBack = onBack,
                            initialSearchQuery = initialPackageSearchQuery
                        )
                    },
                    aiSettingsContent = { onBack ->
                        RikkaHubEmbeddedSettingsPane(
                            onNavigateBack = onBack
                        )
                    },
                    onNavigateToDependencyInstall = {
                        startActivity(DependencyInstallActivity.createIntent(this@SettingsActivity))
                    },
                    onNavigateToPRootLog = {
                        startActivity(Intent(this@SettingsActivity, PRootLogActivity::class.java))
                    },
                    onNavigateToLicenses = {
                        startActivity(Intent(this@SettingsActivity, OpenSourceLicensesActivity::class.java))
                    }
                )
            }
        }
    }

    private fun warmUpRikkaHubSettings() {
        lifecycleScope.launch(Dispatchers.Default) {
            runCatching {
                RikkaHubEmbeddedWarmup.warmup(application)
            }.onFailure { error ->
                Timber.tag(TAG).w(error, "RikkaHub embedded warmup failed")
            }
        }
    }
}
