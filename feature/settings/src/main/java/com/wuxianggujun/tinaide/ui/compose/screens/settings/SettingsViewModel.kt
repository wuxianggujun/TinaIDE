package com.wuxianggujun.tinaide.ui.compose.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wuxianggujun.tinaide.core.config.AppTheme
import com.wuxianggujun.tinaide.core.config.CUSTOM_EDITOR_THEME_ID
import com.wuxianggujun.tinaide.core.config.ConfigKeys
import com.wuxianggujun.tinaide.core.config.CustomEditorTheme
import com.wuxianggujun.tinaide.core.config.DebugToolbarPosition
import com.wuxianggujun.tinaide.core.config.IConfigManager
import com.wuxianggujun.tinaide.core.config.MTFileProviderManager
import com.wuxianggujun.tinaide.core.config.NewProjectSourceLocation
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.config.ThemeManager
import com.wuxianggujun.tinaide.core.IAppNavigator
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.linux.LinuxRunModePolicy
import com.wuxianggujun.tinaide.core.linuxdesktop.UbuntuDesktopProvisioner
import com.wuxianggujun.tinaide.core.linuxdesktop.UbuntuLinuxDesktopCoordinator
import com.wuxianggujun.tinaide.core.proot.LinuxDistroRootfsHealthLevel
import com.wuxianggujun.tinaide.core.proot.LinuxDistroRootfsHealthProbe
import com.wuxianggujun.tinaide.core.proot.LinuxDistroRootfsHealthReport
import com.wuxianggujun.tinaide.core.proot.RootfsDistroRuntime
import com.wuxianggujun.tinaide.core.proot.RootfsProfile
import com.wuxianggujun.tinaide.core.proot.RootfsProfileStore
import com.wuxianggujun.tinaide.core.proot.SelfHostedLinuxDistroRuntime
import com.wuxianggujun.tinaide.core.proot.toHealthSummary
import com.wuxianggujun.tinaide.file.IProjectContext
import com.wuxianggujun.tinaide.plugin.PluginCapabilities
import com.wuxianggujun.tinaide.plugin.PluginManager
import com.wuxianggujun.tinaide.project.ProjectApkExportSupportResolver
import com.wuxianggujun.tinaide.project.ProjectApkExportType
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val appTheme: AppTheme,
    val editorTheme: String,
    val customEditorTheme: CustomEditorTheme,
    val editorFontSize: Float,
    val editorTabSize: Int,
    val editorWordWrap: Boolean,
    val editorShowLineNumbers: Boolean,
    val editorAutoIndent: Boolean,
    val editorRainbowBrackets: Boolean,
    val editorRainbowBracketsMaxLines: Int,
    val editorFontPath: String,
    val editorCodeFolding: Boolean,
    val editorRenderWhitespace: String,
    val editorInsertSpacesForTabs: Boolean,
    val editorHardwareAcceleration: Boolean,
    val codeFormatStyle: String,
    val compilerOptimizationLevel: String,
    val compilerThreads: Int,
    val lspCompletionLimit: Int,
    val completionCaseSensitive: Boolean,
    val lspSignatureHelpEnabled: Boolean,
    val lspInlayHintsEnabled: Boolean,
    val lspSemanticTokensEnabled: Boolean,
    val lspFoldingRangeEnabled: Boolean,
    // Clangd 设置
    val clangdRunMode: String,
    val clangdBackgroundIndex: Boolean,
    val clangdClangTidy: Boolean,
    val clangdHeaderInsertion: String,
    val clangdCompletionStyle: String,
    val clangdFunctionArgPlaceholders: Boolean,
    // CMake 设置
    val cmakeRunMode: String,
    val clangFormatRunMode: String,
    val makeRunMode: String,
    val linuxEnvironmentEnabled: Boolean,
    val cmakeGenerator: String,
    val cmakeParallelJobs: Int,
    val newProjectDefaultSourceLocation: NewProjectSourceLocation,
    val projectAutoSaveInterval: Int,
    val projectAutoBackup: Boolean,
    val debugToolbarPosition: DebugToolbarPosition,
    // 存储设置
    val rootfsPath: String,
    val rootfsProfiles: List<RootfsProfile> = emptyList(),
    val activeRootfsProfileId: String = "",
    val rootfsInstallInProgress: Boolean = false,
    val rootfsInstallMessage: String = "",
    val rootfsInstallProgress: Float = 0f,
    val rootfsHealth: RootfsHealthUiState = RootfsHealthUiState(),
    val linuxDesktopReady: Boolean = false,
    val linuxDesktopStatusText: String = "",
    val linuxDesktopBusy: Boolean = false,
    val linuxDesktopStarting: Boolean = false,
    val linuxDesktopMessage: String = "",
    val linuxDesktopProgress: Float = 0f,
    // MT 管理器文件提供器
    val mtFileProviderEnabled: Boolean,
    // 当前项目上下文（用于项目设置页）
    val currentProjectName: String? = null,
    val currentProjectRootPath: String? = null,
    val projectApkExportType: ProjectApkExportType? = null,
    // true = 当前处于"指定项目"覆盖模式（从项目列表菜单进入），UI 应隐藏全局项
    val isTargetProjectMode: Boolean = false,
    // 项目级原生依赖路径
    val projectNativeIncludeDirs: List<String> = emptyList(),
    val projectNativeLibraryDirs: List<String> = emptyList(),
    val projectNativeRuntimeDirs: List<String> = emptyList(),
    // 项目级原生编译/链接参数
    val projectNativeCFlags: String = "",
    val projectNativeCppFlags: String = "",
    val projectNativeLdFlags: String = "",
    val projectNativeLdLibs: String = "",
    val projectNativeCMakeArgs: List<String> = emptyList()
) {
    companion object {
        fun fromPrefs(configManager: IConfigManager, linuxEnvironmentEnabled: Boolean): SettingsUiState = SettingsUiState(
            appTheme = Prefs.appTheme,
            editorTheme = Prefs.editorTheme,
            customEditorTheme = Prefs.customEditorTheme,
            editorFontSize = Prefs.editorFontSize,
            editorTabSize = Prefs.editorTabSize,
            editorWordWrap = Prefs.editorWordWrap,
            editorShowLineNumbers = Prefs.editorShowLineNumbers,
            editorAutoIndent = Prefs.editorAutoIndent,
            editorRainbowBrackets = Prefs.editorRainbowBrackets,
            editorRainbowBracketsMaxLines = Prefs.editorRainbowBracketsMaxLines,
            editorFontPath = Prefs.editorFontPath,
            editorCodeFolding = Prefs.editorCodeFolding,
            editorRenderWhitespace = Prefs.editorRenderWhitespace,
            editorInsertSpacesForTabs = Prefs.editorInsertSpacesForTabs,
            editorHardwareAcceleration = Prefs.editorHardwareAcceleration,
            codeFormatStyle = Prefs.codeFormatStyle,
            compilerOptimizationLevel = Prefs.compilerOptimizationLevel,
            compilerThreads = Prefs.compilerThreads,
            lspCompletionLimit = Prefs.lspCompletionLimit,
            completionCaseSensitive = Prefs.completionCaseSensitive,
            lspSignatureHelpEnabled = Prefs.lspSignatureHelpEnabled,
            lspInlayHintsEnabled = Prefs.lspInlayHintsEnabled,
            lspSemanticTokensEnabled = Prefs.lspSemanticTokensEnabled,
            lspFoldingRangeEnabled = Prefs.lspFoldingRangeEnabled,
            // Clangd 设置
            clangdRunMode = Prefs.clangdRunMode,
            clangdBackgroundIndex = Prefs.clangdBackgroundIndex,
            clangdClangTidy = Prefs.clangdClangTidy,
            clangdHeaderInsertion = Prefs.clangdHeaderInsertion,
            clangdCompletionStyle = Prefs.clangdCompletionStyle,
            clangdFunctionArgPlaceholders = Prefs.clangdFunctionArgPlaceholders,
            // CMake 设置
            cmakeRunMode = Prefs.cmakeRunMode,
            clangFormatRunMode = Prefs.clangFormatRunMode,
            makeRunMode = Prefs.makeRunMode,
            linuxEnvironmentEnabled = linuxEnvironmentEnabled,
            cmakeGenerator = Prefs.cmakeGenerator,
            cmakeParallelJobs = Prefs.cmakeParallelJobs,
            newProjectDefaultSourceLocation = Prefs.projectDefaultSourceLocation,
            projectAutoSaveInterval = Prefs.projectAutoSaveInterval,
            projectAutoBackup = Prefs.projectAutoBackup,
            debugToolbarPosition = Prefs.debugToolbarPosition,
            // 存储设置
            rootfsPath = Prefs.rootfsPath,
            // MT 管理器文件提供器
            mtFileProviderEnabled = configManager.get(ConfigKeys.MTFileProviderEnabled)
        )
    }
}

class SettingsViewModel(
    private val configManager: IConfigManager,
    private val pluginManager: PluginManager,
    private val projectContext: IProjectContext,
    private val linuxDesktopCoordinator: UbuntuLinuxDesktopCoordinator,
    private val appNavigator: IAppNavigator,
) : ViewModel() {

    // 保存每个设置路由的垂直滚动偏移（像素）
    // Key = SettingsRoute.route, value = ScrollState.value
    // 采用内存存储，Activity/Process 存活期间可保持滚动位置
    private val scrollOffsetMap: MutableMap<String, Int> = mutableMapOf()

    /**
     * 保存指定路由的垂直滚动偏移（像素）
     */
    fun saveScrollOffsetForRoute(routeId: String, offset: Int) {
        scrollOffsetMap[routeId] = offset
    }

    /**
     * 获取指定路由的已保存滚动偏移，若无则返回 0
     */
    fun getScrollOffsetForRoute(routeId: String): Int = scrollOffsetMap[routeId] ?: 0

    private val _uiState = MutableStateFlow(
        SettingsUiState.fromPrefs(
            configManager = configManager,
            linuxEnvironmentEnabled = pluginManager.hasEnabledCapability(PluginCapabilities.LINUX_ENVIRONMENT),
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /**
     * "指定项目"覆盖模式的项目根路径。
     * - null = 使用 projectContext 的当前会话项目（默认模式）
     * - 非 null = 使用该路径下的 ProjectMetadataStore，不依赖会话
     */
    private var targetProjectRootOverride: File? = null

    /**
     * 被 SettingsActivity 在 onCreate 调用，用于从项目列表菜单进入时绑定目标项目。
     * 传 null 恢复为默认（使用当前会话项目）。
     */
    fun setTargetProjectRoot(path: String?) {
        val next = path?.takeIf { it.isNotBlank() }?.let(::File)
            ?.takeIf { it.exists() && it.isDirectory }
        if (next?.absolutePath == targetProjectRootOverride?.absolutePath) return
        targetProjectRootOverride = next
        refreshProjectDependencyPaths()
    }

    private data class ResolvedTargetProject(
        val root: File,
        val displayName: String,
        val buildDirPath: String?
    )

    private fun resolveTargetProject(): ResolvedTargetProject? {
        targetProjectRootOverride?.let { root ->
            val metadata = ProjectMetadataStore.ensure(root, displayNameFallback = root.name)
            return ResolvedTargetProject(
                root = root,
                displayName = metadata.displayName,
                buildDirPath = null // 覆盖模式不做 APK 导出检测
            )
        }
        val project = projectContext.getCurrentProject() ?: return null
        return ResolvedTargetProject(
            root = File(project.rootPath),
            displayName = project.name,
            buildDirPath = project.buildDirPath
        )
    }

    init {
        viewModelScope.launch {
            // 响应式订阅：StateFlow 新订阅者会收到当前值，等价于原先 init 一次性调用；
            // 当 MainPortalActivity.onStart 调用 clearInMemorySession 或用户打开/关闭
            // 项目时 flow 会发射，设置页 UI 随之同步。
            projectContext.currentProjectFlow.collect {
                refreshProjectDependencyPaths()
            }
        }

        viewModelScope.launch {
            ThemeManager.themeFlow.collect { theme ->
                _uiState.update { it.copy(appTheme = theme) }
            }
        }

        viewModelScope.launch {
            Prefs.editorThemeFlow.collect { themeId ->
                _uiState.update { it.copy(editorTheme = themeId) }
            }
        }

        viewModelScope.launch {
            Prefs.customEditorThemeFlow.collect { theme ->
                _uiState.update { it.copy(customEditorTheme = theme) }
            }
        }

        viewModelScope.launch {
            Prefs.debugToolbarPositionFlow.collect { position ->
                _uiState.update { it.copy(debugToolbarPosition = position) }
            }
        }

        viewModelScope.launch {
            pluginManager.enabledCapabilitiesFlow.collect { capabilities ->
                updatePrefsState(PluginCapabilities.LINUX_ENVIRONMENT in capabilities)
                refreshProjectDependencyPaths()
            }
        }
    }

    fun refreshFromPrefs() {
        updatePrefsState(pluginManager.hasEnabledCapability(PluginCapabilities.LINUX_ENVIRONMENT))
        refreshProjectDependencyPaths()
    }

    private fun updatePrefsState(linuxEnvironmentEnabled: Boolean) {
        val previousState = _uiState.value
        _uiState.value = SettingsUiState.fromPrefs(
            configManager = configManager,
            linuxEnvironmentEnabled = linuxEnvironmentEnabled
        ).copy(
            currentProjectName = previousState.currentProjectName,
            currentProjectRootPath = previousState.currentProjectRootPath,
            projectApkExportType = previousState.projectApkExportType,
            isTargetProjectMode = previousState.isTargetProjectMode,
            projectNativeIncludeDirs = previousState.projectNativeIncludeDirs,
            projectNativeLibraryDirs = previousState.projectNativeLibraryDirs,
            projectNativeRuntimeDirs = previousState.projectNativeRuntimeDirs,
            projectNativeCFlags = previousState.projectNativeCFlags,
            projectNativeCppFlags = previousState.projectNativeCppFlags,
            projectNativeLdFlags = previousState.projectNativeLdFlags,
            projectNativeLdLibs = previousState.projectNativeLdLibs,
            projectNativeCMakeArgs = previousState.projectNativeCMakeArgs,
            rootfsProfiles = previousState.rootfsProfiles,
            activeRootfsProfileId = previousState.activeRootfsProfileId,
            rootfsInstallInProgress = previousState.rootfsInstallInProgress,
            rootfsInstallMessage = previousState.rootfsInstallMessage,
            rootfsInstallProgress = previousState.rootfsInstallProgress,
            rootfsHealth = previousState.rootfsHealth,
            linuxDesktopReady = previousState.linuxDesktopReady,
            linuxDesktopStatusText = previousState.linuxDesktopStatusText,
            linuxDesktopBusy = previousState.linuxDesktopBusy,
            linuxDesktopStarting = previousState.linuxDesktopStarting,
            linuxDesktopMessage = previousState.linuxDesktopMessage,
            linuxDesktopProgress = previousState.linuxDesktopProgress,
        )
    }

    private fun resolveRunMode(mode: String): String = LinuxRunModePolicy.resolveValue(
        configuredMode = mode,
        linuxEnvironmentAvailable = _uiState.value.linuxEnvironmentEnabled
    )

    fun setAppTheme(theme: AppTheme) {
        Prefs.setTheme(theme)
        _uiState.update { it.copy(appTheme = theme) }
    }

    fun setEditorFontSize(sizeSp: Float) {
        Prefs.setEditorFontSize(sizeSp)
        _uiState.update { it.copy(editorFontSize = sizeSp) }
    }

    fun setEditorTheme(themeId: String) {
        Prefs.setEditorTheme(themeId)
        _uiState.update { it.copy(editorTheme = themeId) }
    }

    fun setCustomEditorTheme(theme: CustomEditorTheme) {
        val sanitized = theme.sanitized()
        Prefs.setCustomEditorTheme(sanitized)
        Prefs.setEditorTheme(CUSTOM_EDITOR_THEME_ID)
        _uiState.update {
            it.copy(
                editorTheme = CUSTOM_EDITOR_THEME_ID,
                customEditorTheme = sanitized
            )
        }
    }

    fun setEditorTabSize(tabSize: Int) {
        Prefs.setEditorTabSize(tabSize)
        _uiState.update { it.copy(editorTabSize = tabSize) }
    }

    fun setEditorWordWrap(enabled: Boolean) {
        Prefs.setEditorWordWrap(enabled)
        _uiState.update { it.copy(editorWordWrap = enabled) }
    }

    fun setEditorShowLineNumbers(enabled: Boolean) {
        Prefs.setEditorShowLineNumbers(enabled)
        _uiState.update { it.copy(editorShowLineNumbers = enabled) }
    }

    fun setEditorAutoIndent(enabled: Boolean) {
        Prefs.setEditorAutoIndent(enabled)
        _uiState.update { it.copy(editorAutoIndent = enabled) }
    }

    fun setEditorRainbowBrackets(enabled: Boolean) {
        Prefs.setEditorRainbowBrackets(enabled)
        _uiState.update { it.copy(editorRainbowBrackets = enabled) }
    }

    fun setEditorRainbowBracketsMaxLines(maxLines: Int) {
        Prefs.setEditorRainbowBracketsMaxLines(maxLines)
        _uiState.update { it.copy(editorRainbowBracketsMaxLines = maxLines) }
    }

    fun setEditorCodeFolding(enabled: Boolean) {
        Prefs.setEditorCodeFolding(enabled)
        _uiState.update { it.copy(editorCodeFolding = enabled) }
    }

    fun setEditorRenderWhitespace(mode: String) {
        Prefs.setEditorRenderWhitespace(mode)
        _uiState.update { it.copy(editorRenderWhitespace = mode) }
    }

    fun setEditorInsertSpacesForTabs(enabled: Boolean) {
        Prefs.setEditorInsertSpacesForTabs(enabled)
        _uiState.update { it.copy(editorInsertSpacesForTabs = enabled) }
    }

    fun setEditorHardwareAcceleration(enabled: Boolean) {
        Prefs.setEditorHardwareAcceleration(enabled)
        _uiState.update { it.copy(editorHardwareAcceleration = enabled) }
    }

    fun setEditorFontPath(path: String) {
        Prefs.setEditorFontPath(path)
        _uiState.update { it.copy(editorFontPath = path) }
    }

    fun setCodeFormatStyle(style: String) {
        Prefs.setCodeFormatStyle(style)
        _uiState.update { it.copy(codeFormatStyle = style) }
    }

    fun setCompilerOptimizationLevel(level: String) {
        Prefs.setCompilerOptimizationLevel(level)
        _uiState.update { it.copy(compilerOptimizationLevel = level) }
    }

    fun setCompilerThreads(threads: Int) {
        Prefs.setCompilerThreads(threads)
        _uiState.update { it.copy(compilerThreads = threads) }
    }

    fun setLspCompletionLimit(limit: Int) {
        Prefs.setLspCompletionLimit(limit)
        _uiState.update { it.copy(lspCompletionLimit = limit) }
    }

    fun setCompletionCaseSensitive(enabled: Boolean) {
        Prefs.setCompletionCaseSensitive(enabled)
        _uiState.update { it.copy(completionCaseSensitive = enabled) }
    }

    fun setLspSignatureHelpEnabled(enabled: Boolean) {
        Prefs.setLspSignatureHelpEnabled(enabled)
        _uiState.update { it.copy(lspSignatureHelpEnabled = enabled) }
    }

    fun setLspInlayHintsEnabled(enabled: Boolean) {
        Prefs.setLspInlayHintsEnabled(enabled)
        _uiState.update { it.copy(lspInlayHintsEnabled = enabled) }
    }

    fun setLspSemanticTokensEnabled(enabled: Boolean) {
        Prefs.setLspSemanticTokensEnabled(enabled)
        _uiState.update { it.copy(lspSemanticTokensEnabled = enabled) }
    }

    fun setLspFoldingRangeEnabled(enabled: Boolean) {
        Prefs.setLspFoldingRangeEnabled(enabled)
        _uiState.update { it.copy(lspFoldingRangeEnabled = enabled) }
    }

    // Clangd 设置方法

    fun setClangdRunMode(mode: String) {
        val resolvedMode = resolveRunMode(mode)
        Prefs.setClangdRunMode(resolvedMode)
        _uiState.update { it.copy(clangdRunMode = resolvedMode) }
    }

    fun setClangdBackgroundIndex(enabled: Boolean) {
        Prefs.setClangdBackgroundIndex(enabled)
        _uiState.update { it.copy(clangdBackgroundIndex = enabled) }
    }

    fun setClangdClangTidy(enabled: Boolean) {
        Prefs.setClangdClangTidy(enabled)
        _uiState.update { it.copy(clangdClangTidy = enabled) }
    }

    fun setClangdHeaderInsertion(mode: String) {
        Prefs.setClangdHeaderInsertion(mode)
        _uiState.update { it.copy(clangdHeaderInsertion = mode) }
    }

    fun setClangdCompletionStyle(style: String) {
        Prefs.setClangdCompletionStyle(style)
        _uiState.update { it.copy(clangdCompletionStyle = style) }
    }

    fun setClangdFunctionArgPlaceholders(enabled: Boolean) {
        Prefs.setClangdFunctionArgPlaceholders(enabled)
        _uiState.update { it.copy(clangdFunctionArgPlaceholders = enabled) }
    }

    // CMake 设置方法

    fun setCmakeRunMode(mode: String) {
        val resolvedMode = resolveRunMode(mode)
        Prefs.setCmakeRunMode(resolvedMode)
        _uiState.update { it.copy(cmakeRunMode = resolvedMode) }
    }

    fun setClangFormatRunMode(mode: String) {
        val resolvedMode = resolveRunMode(mode)
        Prefs.setClangFormatRunMode(resolvedMode)
        _uiState.update { it.copy(clangFormatRunMode = resolvedMode) }
    }

    fun setMakeRunMode(mode: String) {
        val resolvedMode = resolveRunMode(mode)
        Prefs.setMakeRunMode(resolvedMode)
        _uiState.update { it.copy(makeRunMode = resolvedMode) }
    }

    fun setCmakeGenerator(generator: String) {
        Prefs.setCmakeGenerator(generator)
        _uiState.update { it.copy(cmakeGenerator = generator) }
    }

    fun setCmakeParallelJobs(jobs: Int) {
        Prefs.setCmakeParallelJobs(jobs)
        _uiState.update { it.copy(cmakeParallelJobs = jobs) }
    }

    fun setNewProjectDefaultSourceLocation(location: NewProjectSourceLocation) {
        Prefs.setProjectDefaultSourceLocation(location)
        _uiState.update { it.copy(newProjectDefaultSourceLocation = location) }
    }

    fun setProjectAutoSaveInterval(seconds: Int) {
        Prefs.setProjectAutoSaveInterval(seconds)
        _uiState.update { it.copy(projectAutoSaveInterval = seconds) }
    }

    fun setProjectAutoBackup(enabled: Boolean) {
        Prefs.setProjectAutoBackup(enabled)
        _uiState.update { it.copy(projectAutoBackup = enabled) }
    }

    fun setDebugToolbarPosition(position: DebugToolbarPosition) {
        Prefs.setDebugToolbarPosition(position)
    }

    // 存储设置方法

    fun refreshRootfsProfiles(context: Context) {
        val appContext = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            val store = RootfsProfileStore(appContext, configManager)
            applyRootfsProfilesSnapshot(store)
            updateRootfsHealthSnapshot(appContext)
            updateLinuxDesktopStatusSnapshot(appContext)
        }
    }

    fun switchActiveRootfsProfile(context: Context, profileId: String) {
        val appContext = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    rootfsInstallInProgress = true,
                    rootfsInstallMessage = Strings.settings_linux_switching.strOr(appContext),
                    rootfsInstallProgress = 0f,
                )
            }

            runCatching {
                val store = RootfsProfileStore(appContext, configManager)
                val activeProfile = store.setActiveProfileForDistro(
                    profileId = profileId,
                    distroId = SelfHostedLinuxDistroRuntime.DEFAULT_DISTRO_ID,
                )
                applyRootfsProfilesSnapshot(
                    store = store,
                    inProgress = false,
                    message = Strings.settings_linux_switch_success.strOr(appContext, activeProfile.displayName),
                )
                updateRootfsHealthSnapshot(appContext)
                updateLinuxDesktopStatusSnapshot(appContext)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        rootfsInstallInProgress = false,
                        rootfsInstallMessage = Strings.settings_linux_switch_failed.strOr(
                            appContext,
                            error.message ?: Strings.error_unknown.strOr(appContext)
                        ),
                        rootfsInstallProgress = 0f,
                    )
                }
            }
        }
    }

    fun listRootfsDistroOptions(context: Context): List<RootfsDistroRuntime.DistroOption> = RootfsDistroRuntime(context, configManager).listDistros()

    fun refreshRootfsHealth(context: Context) {
        val appContext = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            updateRootfsHealthSnapshot(appContext)
        }
    }

    fun refreshLinuxDesktopStatus(context: Context) {
        val appContext = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            updateLinuxDesktopStatusSnapshot(appContext)
        }
    }

    fun installUbuntuDesktop(context: Context) {
        if (_uiState.value.linuxDesktopBusy) return
        val appContext = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    linuxDesktopBusy = true,
                    linuxDesktopStarting = false,
                    linuxDesktopMessage = Strings.settings_linux_desktop_checking.strOr(appContext),
                    linuxDesktopProgress = 0f,
                )
            }

            runCatching {
                linuxDesktopCoordinator.install { progress ->
                    _uiState.update {
                        it.copy(
                            linuxDesktopMessage = progress.phase.toDesktopMessage(appContext),
                            linuxDesktopProgress = progress.progress.coerceIn(0f, 1f),
                        )
                    }
                }.getOrThrow()
            }.onSuccess { result ->
                _uiState.update {
                    it.copy(
                        linuxDesktopBusy = false,
                        linuxDesktopStarting = false,
                        linuxDesktopReady = result.status.ready,
                        linuxDesktopStatusText = result.status.toStatusText(appContext),
                        linuxDesktopMessage = Strings.settings_linux_desktop_install_success.strOr(appContext),
                        linuxDesktopProgress = 1f,
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        linuxDesktopBusy = false,
                        linuxDesktopStarting = false,
                        linuxDesktopReady = false,
                        linuxDesktopMessage = Strings.settings_linux_desktop_install_failed.strOr(
                            appContext,
                            error.message ?: Strings.error_unknown.strOr(appContext),
                        ),
                        linuxDesktopProgress = 0f,
                    )
                }
            }
        }
    }

    fun openLinuxDesktop(context: Context) {
        if (_uiState.value.linuxDesktopBusy) return
        val appContext = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    linuxDesktopBusy = true,
                    linuxDesktopStarting = true,
                    linuxDesktopMessage = Strings.settings_linux_desktop_starting.strOr(appContext),
                    linuxDesktopProgress = 0.3f,
                )
            }

            runCatching {
                linuxDesktopCoordinator.startSession().getOrThrow()
                withContext(Dispatchers.Main) {
                    appNavigator.openLinuxDesktop(appContext)
                }
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        linuxDesktopBusy = false,
                        linuxDesktopStarting = false,
                        linuxDesktopMessage = "",
                        linuxDesktopProgress = 1f,
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        linuxDesktopBusy = false,
                        linuxDesktopStarting = false,
                        linuxDesktopMessage = Strings.settings_linux_desktop_open_failed.strOr(
                            appContext,
                            error.message ?: Strings.error_unknown.strOr(appContext),
                        ),
                        linuxDesktopProgress = 0f,
                    )
                }
            }
        }
    }

    fun installRootfsDistro(context: Context, distro: RootfsDistroRuntime.DistroOption) {
        val appContext = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    rootfsInstallInProgress = true,
                    rootfsInstallMessage = Strings.settings_linux_installing.strOr(appContext),
                    rootfsInstallProgress = 0f,
                )
            }

            runCatching {
                val runtime = RootfsDistroRuntime(appContext, configManager)
                val profile = runtime.installDistro(
                    distroId = distro.id,
                ) { installProgress ->
                    updateRootfsDistroInstallProgress(installProgress)
                }.getOrThrow()
                val store = RootfsProfileStore(appContext, configManager)
                applyRootfsProfilesSnapshot(
                    store = store,
                    inProgress = false,
                    message = Strings.settings_linux_install_success.strOr(appContext, profile.displayName),
                    progress = 1f,
                )
                updateRootfsHealthSnapshot(appContext)
                updateLinuxDesktopStatusSnapshot(appContext)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        rootfsInstallInProgress = false,
                        rootfsInstallMessage = Strings.settings_linux_install_failed.strOr(
                            appContext,
                            error.message ?: Strings.error_unknown.strOr(appContext)
                        ),
                        rootfsInstallProgress = 0f,
                    )
                }
            }
        }
    }

    fun renameRootfsProfile(context: Context, profileId: String, displayName: String) {
        val normalizedName = displayName.trim()
        if (normalizedName.isEmpty()) return

        val appContext = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    rootfsInstallInProgress = true,
                    rootfsInstallMessage = Strings.settings_linux_renaming.strOr(appContext),
                    rootfsInstallProgress = 0f,
                )
            }

            runCatching {
                val store = RootfsProfileStore(appContext, configManager)
                val profile = store.renameProfile(profileId, normalizedName)
                applyRootfsProfilesSnapshot(
                    store = store,
                    inProgress = false,
                    message = Strings.settings_linux_rename_success.strOr(appContext, profile.displayName),
                )
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        rootfsInstallInProgress = false,
                        rootfsInstallMessage = Strings.settings_linux_rename_failed.strOr(
                            appContext,
                            error.message ?: Strings.error_unknown.strOr(appContext)
                        ),
                        rootfsInstallProgress = 0f,
                    )
                }
            }
        }
    }

    fun deleteRootfsProfile(context: Context, profileId: String) {
        val appContext = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    rootfsInstallInProgress = true,
                    rootfsInstallMessage = Strings.settings_linux_deleting.strOr(appContext),
                    rootfsInstallProgress = 0f,
                )
            }

            runCatching {
                val store = RootfsProfileStore(appContext, configManager)
                val deletedProfile = store.deleteProfile(profileId)
                applyRootfsProfilesSnapshot(
                    store = store,
                    inProgress = false,
                    message = Strings.settings_linux_delete_success.strOr(appContext, deletedProfile.displayName),
                )
                updateRootfsHealthSnapshot(appContext)
                updateLinuxDesktopStatusSnapshot(appContext)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        rootfsInstallInProgress = false,
                        rootfsInstallMessage = Strings.settings_linux_delete_failed.strOr(
                            appContext,
                            error.message ?: Strings.error_unknown.strOr(appContext)
                        ),
                        rootfsInstallProgress = 0f,
                    )
                }
            }
        }
    }

    /**
     * 设置 MT 管理器文件提供器开关
     * 启用后允许 MT 管理器访问应用私有目录
     *
     * @param context 上下文，用于动态启用/禁用组件
     * @param enabled 是否启用
     */
    fun setMTFileProviderEnabled(context: android.content.Context, enabled: Boolean) {
        configManager.set(ConfigKeys.MTFileProviderEnabled, enabled)
        _uiState.update { it.copy(mtFileProviderEnabled = enabled) }
        // 动态启用/禁用 Provider 组件
        MTFileProviderManager.setProviderEnabled(context, enabled)
    }

    fun updateProjectNativeDependencyPaths(
        includeDirs: List<String>,
        libraryDirs: List<String>,
        runtimeDirs: List<String>
    ): Boolean {
        val resolved = resolveTargetProject() ?: return false
        ProjectMetadataStore.ensure(resolved.root, displayNameFallback = resolved.displayName)
        val updated = ProjectMetadataStore.updateNativeDependencyPaths(
            projectRoot = resolved.root,
            includeDirs = includeDirs,
            libraryDirs = libraryDirs,
            runtimeDirs = runtimeDirs
        )
        if (updated) {
            refreshProjectDependencyPaths()
        }
        return updated
    }

    fun updateProjectNativeBuildFlags(
        cFlags: String,
        cppFlags: String,
        ldFlags: String,
        ldLibs: String,
        cmakeArgs: List<String>
    ): Boolean {
        val resolved = resolveTargetProject() ?: return false
        ProjectMetadataStore.ensure(resolved.root, displayNameFallback = resolved.displayName)
        val updated = ProjectMetadataStore.updateNativeBuildFlags(
            projectRoot = resolved.root,
            cFlags = cFlags,
            cppFlags = cppFlags,
            ldFlags = ldFlags,
            ldLibs = ldLibs,
            cmakeArgs = cmakeArgs
        )
        if (updated) {
            refreshProjectDependencyPaths()
        }
        return updated
    }

    fun updateProjectApkExportType(apkExportType: ProjectApkExportType): Boolean {
        val resolved = resolveTargetProject() ?: return false
        val updated = ProjectMetadataStore.updateApkExportType(resolved.root, apkExportType)
        if (updated) {
            refreshProjectDependencyPaths()
        }
        return updated
    }

    fun redetectProjectApkExportType(): Boolean {
        val resolved = resolveTargetProject() ?: return false
        // 覆盖模式下没有 buildDir，无法做自动检测；refreshProjectDependencyPaths 里也会跳过。
        // 仍允许把 apkExportType 重置为 null，下次进入时显示"未检测"。
        ProjectMetadataStore.ensure(resolved.root, displayNameFallback = resolved.displayName)
        val reset = ProjectMetadataStore.updateApkExportType(resolved.root, null)
        refreshProjectDependencyPaths()
        return reset
    }

    private fun refreshProjectDependencyPaths() {
        val resolved = resolveTargetProject()
        val inTargetMode = targetProjectRootOverride != null
        if (resolved == null) {
            _uiState.update {
                it.copy(
                    currentProjectName = null,
                    currentProjectRootPath = null,
                    projectApkExportType = null,
                    isTargetProjectMode = inTargetMode,
                    projectNativeIncludeDirs = emptyList(),
                    projectNativeLibraryDirs = emptyList(),
                    projectNativeRuntimeDirs = emptyList(),
                    projectNativeCFlags = "",
                    projectNativeCppFlags = "",
                    projectNativeLdFlags = "",
                    projectNativeLdLibs = "",
                    projectNativeCMakeArgs = emptyList()
                )
            }
            return
        }

        val metadata = ProjectMetadataStore.ensure(
            projectRoot = resolved.root,
            displayNameFallback = resolved.displayName
        )
        // 覆盖模式下 buildDirPath 为 null，不做自动检测——只读持久化的 apkExportType；
        // 默认模式下保留原有"缺失即自动检测"的行为。
        val apkExportType = metadata.apkExportType
            ?: resolved.buildDirPath?.let { buildPath ->
                ProjectApkExportSupportResolver.ensureDetected(
                    projectRoot = resolved.root,
                    buildDir = File(buildPath)
                )
            }
        _uiState.update {
            it.copy(
                currentProjectName = resolved.displayName,
                currentProjectRootPath = resolved.root.absolutePath,
                projectApkExportType = apkExportType,
                isTargetProjectMode = inTargetMode,
                projectNativeIncludeDirs = metadata.normalizedNativeIncludeDirs(),
                projectNativeLibraryDirs = metadata.normalizedNativeLibraryDirs(),
                projectNativeRuntimeDirs = metadata.normalizedNativeRuntimeDirs(),
                projectNativeCFlags = metadata.normalizedNativeCFlags(),
                projectNativeCppFlags = metadata.normalizedNativeCppFlags(),
                projectNativeLdFlags = metadata.normalizedNativeLdFlags(),
                projectNativeLdLibs = metadata.normalizedNativeLdLibs(),
                projectNativeCMakeArgs = metadata.normalizedNativeCMakeArgs()
            )
        }
    }

    private fun applyRootfsProfilesSnapshot(
        store: RootfsProfileStore,
        inProgress: Boolean = _uiState.value.rootfsInstallInProgress,
        message: String = _uiState.value.rootfsInstallMessage,
        progress: Float = _uiState.value.rootfsInstallProgress,
    ) {
        val activeProfile = store.getActiveProfileForDistro(SelfHostedLinuxDistroRuntime.DEFAULT_DISTRO_ID)
        val profiles = store.listProfilesForDistro(SelfHostedLinuxDistroRuntime.DEFAULT_DISTRO_ID)
        _uiState.update {
            it.copy(
                rootfsPath = activeProfile?.rootfsPath.orEmpty(),
                rootfsProfiles = profiles,
                activeRootfsProfileId = activeProfile?.id.orEmpty(),
                rootfsInstallInProgress = inProgress,
                rootfsInstallMessage = message,
                rootfsInstallProgress = progress,
            )
        }
    }

    private fun updateRootfsDistroInstallProgress(progress: RootfsDistroRuntime.InstallProgress) {
        _uiState.update {
            it.copy(
                rootfsInstallInProgress = !progress.completed,
                rootfsInstallMessage = progress.message,
                rootfsInstallProgress = progress.progress.coerceIn(0f, 1f),
            )
        }
    }

    private suspend fun updateRootfsHealthSnapshot(appContext: Context) {
        _uiState.update {
            it.copy(
                rootfsHealth = RootfsHealthUiState(
                    status = RootfsHealthStatus.CHECKING,
                    statusText = Strings.settings_linux_health_checking.strOr(appContext),
                )
            )
        }

        RootfsDistroRuntime(appContext, configManager)
            .checkActiveDistroHealth()
            .onSuccess { report ->
                _uiState.update { state ->
                    state.copy(rootfsHealth = report.toRootfsHealthUiState(appContext))
                }
            }
            .onFailure { error ->
                _uiState.update { state ->
                    state.copy(
                        rootfsHealth = RootfsHealthUiState(
                            status = RootfsHealthStatus.UNAVAILABLE,
                            statusText = Strings.settings_linux_health_unavailable.strOr(appContext),
                            detailText = Strings.settings_linux_health_check_failed.strOr(
                                appContext,
                                error.message ?: Strings.error_unknown.strOr(appContext),
                            ),
                        )
                    )
                }
            }
    }

    private suspend fun updateLinuxDesktopStatusSnapshot(appContext: Context) {
        if (_uiState.value.linuxDesktopBusy) return
        if (!_uiState.value.linuxEnvironmentEnabled) {
            _uiState.update {
                it.copy(
                    linuxDesktopReady = false,
                    linuxDesktopStatusText = "",
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                linuxDesktopStatusText = Strings.settings_linux_desktop_checking.strOr(appContext),
            )
        }

        runCatching { linuxDesktopCoordinator.inspect() }
            .onSuccess { status ->
                _uiState.update {
                    it.copy(
                        linuxDesktopReady = status.ready,
                        linuxDesktopStatusText = status.toStatusText(appContext),
                    )
                }
            }
            .onFailure { error ->
                _uiState.update {
                    it.copy(
                        linuxDesktopReady = false,
                        linuxDesktopStatusText = error.message
                            ?: Strings.settings_linux_desktop_not_ready.strOr(appContext),
                    )
                }
            }
    }

    private fun UbuntuDesktopProvisioner.Status.toStatusText(appContext: Context): String =
        if (ready) {
            Strings.settings_linux_desktop_ready.strOr(appContext)
        } else {
            Strings.settings_linux_desktop_not_ready.strOr(appContext)
        }

    private fun UbuntuDesktopProvisioner.Phase.toDesktopMessage(appContext: Context): String =
        when (this) {
            UbuntuDesktopProvisioner.Phase.CHECKING ->
                Strings.settings_linux_desktop_checking.strOr(appContext)
            UbuntuDesktopProvisioner.Phase.UPDATING_INDEX ->
                Strings.settings_linux_desktop_updating.strOr(appContext)
            UbuntuDesktopProvisioner.Phase.INSTALLING_PACKAGES ->
                Strings.settings_linux_desktop_installing.strOr(appContext)
            UbuntuDesktopProvisioner.Phase.VERIFYING ->
                Strings.settings_linux_desktop_verifying.strOr(appContext)
            UbuntuDesktopProvisioner.Phase.COMPLETED ->
                Strings.settings_linux_desktop_install_success.strOr(appContext)
        }

    private fun LinuxDistroRootfsHealthReport.toRootfsHealthUiState(appContext: Context): RootfsHealthUiState {
        val summary = toHealthSummary { probe -> probe.toDisplayName(appContext) }
        val status = when (summary.level) {
            LinuxDistroRootfsHealthLevel.READY -> RootfsHealthStatus.READY
            LinuxDistroRootfsHealthLevel.ATTENTION -> RootfsHealthStatus.ATTENTION
            LinuxDistroRootfsHealthLevel.UNAVAILABLE -> RootfsHealthStatus.UNAVAILABLE
        }
        val statusText = when (status) {
            RootfsHealthStatus.READY -> Strings.settings_linux_health_ready.strOr(appContext)
            RootfsHealthStatus.ATTENTION -> Strings.settings_linux_health_attention.strOr(appContext)
            RootfsHealthStatus.UNAVAILABLE -> Strings.settings_linux_health_unavailable.strOr(appContext)
            RootfsHealthStatus.CHECKING -> Strings.settings_linux_health_checking.strOr(appContext)
            RootfsHealthStatus.UNKNOWN -> Strings.settings_linux_health_unknown.strOr(appContext)
        }
        val detailText = when {
            summary.requiredMissingItems.isNotEmpty() -> Strings.settings_linux_health_missing_required.strOr(
                appContext,
                summary.requiredMissingItems.joinToString(),
            )
            summary.optionalMissingItems.isNotEmpty() -> Strings.settings_linux_health_missing_optional.strOr(
                appContext,
                summary.optionalMissingItems.joinToString(),
            )
            summary.identity.isNotBlank() -> summary.identity
            else -> ""
        }

        return RootfsHealthUiState(
            status = status,
            statusText = statusText,
            detailText = detailText,
        )
    }

    private fun LinuxDistroRootfsHealthProbe.toDisplayName(appContext: Context): String = when (this) {
        LinuxDistroRootfsHealthProbe.ROOTFS_AVAILABLE ->
            Strings.linux_distro_health_probe_rootfs_available.strOr(appContext)
        LinuxDistroRootfsHealthProbe.PACKAGE_MANAGER_COMMANDS ->
            Strings.linux_distro_health_probe_package_manager_commands.strOr(appContext)
        LinuxDistroRootfsHealthProbe.PACKAGE_MANAGER_VERSION ->
            Strings.linux_distro_health_probe_package_manager_version.strOr(appContext)
        LinuxDistroRootfsHealthProbe.REQUIRED_BOOTSTRAP_COMMANDS ->
            Strings.linux_distro_health_probe_required_commands.strOr(appContext)
        LinuxDistroRootfsHealthProbe.OPTIONAL_BOOTSTRAP_COMMANDS ->
            Strings.linux_distro_health_probe_optional_commands.strOr(appContext)
        LinuxDistroRootfsHealthProbe.ARCHITECTURE ->
            Strings.linux_distro_health_probe_architecture.strOr(appContext)
        LinuxDistroRootfsHealthProbe.OS_RELEASE ->
            Strings.linux_distro_health_probe_os_release.strOr(appContext)
    }
}
