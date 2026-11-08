package com.wuxianggujun.tinaide.ui.compose.screens.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.proot.PRootBootstrap
import com.wuxianggujun.tinaide.plugin.EditorThemeIndex
import com.wuxianggujun.tinaide.plugin.PluginLogLevel
import com.wuxianggujun.tinaide.plugin.PluginManager
import com.wuxianggujun.tinaide.plugin.lsp.LspPluginManager
import com.wuxianggujun.tinaide.ui.compose.components.TinaConfirmDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaDropdownMenu
import com.wuxianggujun.tinaide.ui.compose.components.TinaDropdownMenuItem
import com.wuxianggujun.tinaide.ui.compose.components.TinaDropdownMenuSectionHeader
import com.wuxianggujun.tinaide.ui.compose.components.TinaDropdownMenuSectionTitle
import com.wuxianggujun.tinaide.ui.compose.components.TinaTopBar
import com.wuxianggujun.tinaide.ui.compose.screens.settings.sections.AboutSettingsSection
import com.wuxianggujun.tinaide.ui.compose.screens.settings.sections.AppearanceSettingsSection
import com.wuxianggujun.tinaide.ui.compose.screens.settings.sections.CompilerSettingsSection
import com.wuxianggujun.tinaide.ui.compose.screens.settings.sections.DeveloperOptionsSection
import com.wuxianggujun.tinaide.ui.compose.screens.settings.sections.DonationSettingsSection
import com.wuxianggujun.tinaide.ui.compose.screens.settings.sections.EditorSettingsSection
import com.wuxianggujun.tinaide.ui.compose.screens.settings.sections.GitSettingsSection
import com.wuxianggujun.tinaide.ui.compose.screens.settings.sections.KeyboardSettingsSection
import com.wuxianggujun.tinaide.ui.compose.screens.settings.sections.LspSettingsSection
import com.wuxianggujun.tinaide.ui.compose.screens.settings.sections.PluginLogScreen
import com.wuxianggujun.tinaide.ui.compose.screens.settings.sections.PluginMarketplaceScreen
import com.wuxianggujun.tinaide.ui.compose.screens.settings.sections.PluginsSettingsSection
import com.wuxianggujun.tinaide.ui.compose.screens.settings.sections.ProjectSettingsSection
import com.wuxianggujun.tinaide.ui.compose.screens.settings.sections.SettingsRootSection
import com.wuxianggujun.tinaide.ui.compose.screens.settings.sections.StorageCleanupScreen
import com.wuxianggujun.tinaide.ui.compose.screens.settings.sections.StorageSettingsSection
import com.wuxianggujun.tinaide.ui.compose.screens.settings.sections.TerminalSettingsSection
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 设置页面主路由
 */
sealed class SettingsRoute(val route: String, val title: Int) {
    data object Root : SettingsRoute("root", Strings.menu_settings)
    data object Editor : SettingsRoute("editor", Strings.settings_title_editor)
    data object Lsp : SettingsRoute("lsp", Strings.settings_title_lsp)
    data object Compiler : SettingsRoute("compiler", Strings.settings_title_compiler)
    data object Project : SettingsRoute("project", Strings.settings_title_project)
    data object Storage : SettingsRoute("storage", Strings.settings_title_storage)
    data object StorageCleanup : SettingsRoute("storage_cleanup", Strings.settings_title_storage_cleanup)
    data object Terminal : SettingsRoute("terminal", Strings.settings_title_terminal)
    data object Ai : SettingsRoute("ai", Strings.settings_title_ai)
    data object Git : SettingsRoute("git", Strings.settings_title_git)
    data object Appearance : SettingsRoute("appearance", Strings.settings_title_appearance)
    data object Keyboard : SettingsRoute("keyboard", Strings.settings_title_keyboard)
    data object Plugins : SettingsRoute("plugins", Strings.settings_title_plugins)
    data object Packages : SettingsRoute("packages", Strings.settings_title_packages)
    data object PluginMarketplace : SettingsRoute("plugin_marketplace", Strings.plugin_marketplace_title)
    data object PluginLog : SettingsRoute("plugin_log", Strings.plugin_log_title)
    data object Help : SettingsRoute("help", Strings.settings_title_help)
    data object Developer : SettingsRoute("developer", Strings.settings_title_developer)
    data object Donation : SettingsRoute("donation", Strings.donation_title)
    data object About : SettingsRoute("about", Strings.settings_title_about)
}

/**
 * 设置主屏幕
 */
@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun SettingsScreen(
    currentRoute: SettingsRoute,
    settingsViewModel: SettingsViewModel,
    pluginManager: PluginManager,
    themeRegistry: EditorThemeIndex,
    lspPluginManager: LspPluginManager? = null,
    initialPluginIdForDetail: String? = null,
    onNavigateBack: () -> Unit,
    onNavigateTo: (SettingsRoute) -> Unit,
    // Composable slots for screens that live outside this module
    helpContent: (@Composable (onBack: () -> Unit) -> Unit)? = null,
    packagesContent: (@Composable (onBack: () -> Unit) -> Unit)? = null,
    aiSettingsContent: (@Composable (onBack: () -> Unit) -> Unit)? = null,
    // Navigation callbacks for Activity launches
    onNavigateToDependencyInstall: () -> Unit = {},
    onNavigateToPRootLog: () -> Unit = {},
    onNavigateToLicenses: () -> Unit = {}
) {
    val routeResolution = remember(
        currentRoute,
        helpContent,
        packagesContent,
        aiSettingsContent
    ) {
        SettingsScreenSupport.resolveRouteResolution(
            currentRoute = currentRoute,
            hasHelpContent = helpContent != null,
            hasPackagesContent = packagesContent != null,
            hasAiSettingsContent = aiSettingsContent != null
        )
    }

    var selectedPluginLogFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedPluginLogName by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedPluginLogLevelName by rememberSaveable { mutableStateOf<String?>(null) }
    // 插件详情页面状态需要被日志页快捷修复入口复用。
    var selectedPluginIdForDetail by rememberSaveable(initialPluginIdForDetail) {
        mutableStateOf(initialPluginIdForDetail)
    }

    when (routeResolution.host) {
        SettingsScreenHost.HelpContent -> {
            helpContent?.invoke(onNavigateBack)
            return
        }

        SettingsScreenHost.PluginMarketplaceScreen -> {
            PluginMarketplaceScreen(
                onBack = onNavigateBack
            )
            return
        }

        SettingsScreenHost.PluginLogScreen -> {
            PluginLogScreen(
                onBack = onNavigateBack,
                initialPluginId = selectedPluginLogFilter,
                initialPluginName = selectedPluginLogName,
                initialLevel = selectedPluginLogLevelName?.let(PluginLogLevel::valueOf),
                onRepairLspDependencies = { pluginId ->
                    selectedPluginIdForDetail = pluginId
                    selectedPluginLogFilter = null
                    selectedPluginLogName = null
                    selectedPluginLogLevelName = null
                    onNavigateTo(SettingsRoute.Plugins)
                },
            )
            return
        }

        SettingsScreenHost.StorageCleanupScreen -> {
            StorageCleanupScreen(
                onBack = onNavigateBack
            )
            return
        }

        SettingsScreenHost.PackagesContent -> {
            packagesContent?.invoke(onNavigateBack)
            return
        }

        SettingsScreenHost.AiSettingsContent -> {
            aiSettingsContent?.invoke(onNavigateBack)
            return
        }

        else -> Unit
    }

    val context = LocalContext.current
    val settingsState by settingsViewModel.uiState.collectAsState()
    var showLinuxEnvironmentInstallPrompt by rememberSaveable { mutableStateOf(false) }
    var previousLinuxEnvironmentEnabled by rememberSaveable {
        mutableStateOf(settingsState.linuxEnvironmentEnabled)
    }

    LaunchedEffect(settingsState.linuxEnvironmentEnabled) {
        val enabled = settingsState.linuxEnvironmentEnabled
        if (
            SettingsScreenSupport.shouldShowLinuxEnvironmentInstallPrompt(
                previousLinuxEnvironmentEnabled = previousLinuxEnvironmentEnabled,
                currentLinuxEnvironmentEnabled = enabled,
                isEnvironmentReady = PRootBootstrap.isEnvironmentReady(context)
            )
        ) {
            showLinuxEnvironmentInstallPrompt = true
        }
        previousLinuxEnvironmentEnabled = enabled
    }

    val title = stringResource(currentRoute.title)

    // 插件页面的更多菜单状态
    var showPluginMenu by remember { mutableStateOf(false) }
    // 从文件安装插件的触发器
    var installFromFileTrigger by remember { mutableStateOf(0) }
    // 插件详情页面状态（提升到此层级，避免 Scaffold 嵌套导致的双 TopBar）
    val installedPlugins by pluginManager.pluginsFlow.collectAsState()
    val selectedPluginForDetail = selectedPluginIdForDetail?.let { pluginId ->
        installedPlugins.find { it.manifest.id == pluginId }
    }
    // 插件管理模式状态
    var isPluginManageMode by remember { mutableStateOf(false) }
    var selectedForUninstall by remember { mutableStateOf<Set<String>>(emptySet()) }
    var batchUninstallTrigger by remember { mutableStateOf(0) }

    // 是否正在查看插件详情
    val isPluginDetail = currentRoute == SettingsRoute.Plugins && selectedPluginIdForDetail != null
    // 是否处于插件管理模式
    val isPluginManage = currentRoute == SettingsRoute.Plugins && isPluginManageMode
    if (showLinuxEnvironmentInstallPrompt) {
        TinaConfirmDialog(
            title = stringResource(Strings.settings_linux_system),
            message = stringResource(Strings.linux_rootfs_desc),
            confirmText = stringResource(Strings.btn_install),
            onConfirm = {
                showLinuxEnvironmentInstallPrompt = false
                onNavigateToDependencyInstall()
            },
            onDismiss = { showLinuxEnvironmentInstallPrompt = false },
        )
    }

    Scaffold(
        topBar = {
            TinaTopBar(
                title = when {
                    isPluginManage -> stringResource(Strings.settings_plugins_selected_count, selectedForUninstall.size)
                    isPluginDetail -> selectedPluginForDetail?.manifest?.name ?: title
                    else -> title
                },
                onNavigateBack = when {
                    isPluginManage -> {
                        {
                            isPluginManageMode = false
                            selectedForUninstall = emptySet()
                        }
                    }
                    isPluginDetail -> {
                        { selectedPluginIdForDetail = null }
                    }
                    else -> onNavigateBack
                },
                actions = {
                    if (isPluginManage) {
                        // 管理模式：全选/取消全选 + 删除按钮
                        val allSelected = installedPlugins.isNotEmpty() &&
                            installedPlugins.all { it.manifest.id in selectedForUninstall }
                        IconButton(onClick = {
                            selectedForUninstall = if (allSelected) {
                                emptySet()
                            } else {
                                installedPlugins.map { it.manifest.id }.toSet()
                            }
                        }) {
                            Icon(
                                imageVector = if (allSelected) Icons.Default.Deselect else Icons.Default.SelectAll,
                                contentDescription = null
                            )
                        }
                        IconButton(
                            onClick = {
                                // 触发批量卸载 — 通过 PluginsSettingsSection 的 pendingBatchUninstall
                                // 这里用一个 trick：设置一个触发器
                                batchUninstallTrigger++
                            },
                            enabled = selectedForUninstall.isNotEmpty()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                tint = if (selectedForUninstall.isNotEmpty()) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    } else if (currentRoute == SettingsRoute.Plugins && !isPluginDetail) {
                        // 插件列表页面：更多菜单
                        Box {
                            IconButton(onClick = { showPluginMenu = true }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = stringResource(Strings.action_more)
                                )
                            }

                            TinaDropdownMenu(
                                expanded = showPluginMenu,
                                onDismissRequest = { showPluginMenu = false }
                            ) {
                                TinaDropdownMenuSectionHeader {
                                    TinaDropdownMenuSectionTitle(
                                        text = stringResource(Strings.action_more)
                                    )
                                }
                                TinaDropdownMenuItem(
                                    text = { Text(stringResource(Strings.settings_plugins_manage)) },
                                    onClick = {
                                        showPluginMenu = false
                                        isPluginManageMode = true
                                    }
                                )
                                TinaDropdownMenuItem(
                                    text = { Text(stringResource(Strings.settings_plugins_install_from_file)) },
                                    onClick = {
                                        showPluginMenu = false
                                        installFromFileTrigger++
                                    }
                                )
                                TinaDropdownMenuItem(
                                    text = { Text(stringResource(Strings.plugin_log_title)) },
                                    onClick = {
                                        showPluginMenu = false
                                        selectedPluginLogFilter = null
                                        selectedPluginLogName = null
                                        selectedPluginLogLevelName = null
                                        onNavigateTo(SettingsRoute.PluginLog)
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        if (routeResolution.host == SettingsScreenHost.GitSpecialLayout) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                GitSettingsSection()
            }
        } else if (routeResolution.host == SettingsScreenHost.PluginsSpecialLayout) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                PluginsSettingsSection(
                    pluginManager = pluginManager,
                    themeRegistry = themeRegistry,
                    lspPluginManager = lspPluginManager,
                    installFromFileTrigger = installFromFileTrigger,
                    selectedPluginId = selectedPluginIdForDetail,
                    onPluginDetailChanged = { selectedPluginIdForDetail = it },
                    isManageMode = isPluginManageMode,
                    selectedForUninstall = selectedForUninstall,
                    onManageModeChanged = {
                        isPluginManageMode = it
                        if (!it) selectedForUninstall = emptySet()
                    },
                    onSelectionChanged = { selectedForUninstall = it },
                    batchUninstallTrigger = batchUninstallTrigger,
                    onOpenPluginLogs = { pluginId, pluginName, initialLevel ->
                        selectedPluginLogFilter = pluginId
                        selectedPluginLogName = pluginName
                        selectedPluginLogLevelName = initialLevel?.name
                        onNavigateTo(SettingsRoute.PluginLog)
                    }
                )
            }
        } else {
            val scrollState = remember(currentRoute.route) {
                ScrollState(settingsViewModel.getScrollOffsetForRoute(currentRoute.route))
            }

            LaunchedEffect(currentRoute.route, scrollState) {
                snapshotFlow { scrollState.value }
                    .distinctUntilChanged()
                    .debounce(90)
                    .collect { offset ->
                        settingsViewModel.saveScrollOffsetForRoute(currentRoute.route, offset)
                    }
            }

            DisposableEffect(currentRoute.route, scrollState) {
                onDispose {
                    settingsViewModel.saveScrollOffsetForRoute(currentRoute.route, scrollState.value)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .verticalScroll(scrollState)
            ) {
                when (routeResolution.scrollableContent ?: SettingsScrollableContent.Placeholder) {
                    SettingsScrollableContent.Root -> SettingsRootSection(onNavigateTo)
                    SettingsScrollableContent.Editor -> EditorSettingsSection(settingsViewModel)
                    SettingsScrollableContent.Lsp -> LspSettingsSection(settingsViewModel)
                    SettingsScrollableContent.Compiler -> CompilerSettingsSection(settingsViewModel)
                    SettingsScrollableContent.Project -> ProjectSettingsSection(settingsViewModel)
                    SettingsScrollableContent.Storage -> StorageSettingsSection(
                        viewModel = settingsViewModel,
                        onNavigateToDependencyInstall = onNavigateToDependencyInstall,
                        onNavigateToStorageCleanup = { onNavigateTo(SettingsRoute.StorageCleanup) }
                    )
                    SettingsScrollableContent.Terminal -> TerminalSettingsSection(
                        linuxEnvironmentEnabled = settingsState.linuxEnvironmentEnabled
                    )
                    SettingsScrollableContent.Appearance -> AppearanceSettingsSection(settingsViewModel)
                    SettingsScrollableContent.Keyboard -> KeyboardSettingsSection()
                    SettingsScrollableContent.Developer -> DeveloperOptionsSection(
                        onNavigateBack = onNavigateBack
                    )
                    SettingsScrollableContent.About -> AboutSettingsSection(
                        showPRootDiagnostics = settingsState.linuxEnvironmentEnabled,
                        onNavigateToPRootLog = onNavigateToPRootLog,
                        onNavigateToLicenses = onNavigateToLicenses
                    )
                    SettingsScrollableContent.Donation -> DonationSettingsSection()
                    SettingsScrollableContent.Placeholder -> Unit
                }
            }
        }
    }
}
