package com.wuxianggujun.tinaide.core.config

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import com.wuxianggujun.tinaide.core.font.AppFontManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 编辑器设置数据类
 * 用于统一管理编辑器相关设置的响应式更新
 */
data class EditorSettings(
    val fontSize: Float,
    val tabSize: Int,
    val wordWrap: Boolean,
    val showLineNumbers: Boolean,
    val autoIndent: Boolean,
    val rainbowBrackets: Boolean,
    val rainbowBracketsMaxLines: Int,
    val fontPath: String,
    val codeFolding: Boolean,
    val renderWhitespace: String,
    val insertSpacesForTabs: Boolean,
    val scrollFlingEnabled: Boolean,
    val singleDirectionDragging: Boolean,
    val singleDirectionFling: Boolean
)

data class DeveloperDiagnosticsSettings(
    val diagnosticsEnabled: Boolean,
    val buildDiagnosticsLogEnabled: Boolean,
    val lspCompileCommandsSelectionLogEnabled: Boolean,
    val lspClangdStartupLogEnabled: Boolean,
    val editorTouchDiagnosticsEnabled: Boolean,
    val gestureTraceEnabled: Boolean,
    val editorInternalTouchLogEnabled: Boolean,
    val editorScaleLogEnabled: Boolean,
    val editorFocusLogEnabled: Boolean,
    val editorScrollLogEnabled: Boolean,
    val editorFlingLogEnabled: Boolean
)

/**
 * TinaIDE 配置访问门面。
 *
 * 目标：
 * - 给调用方提供简单、类型安全的访问入口（避免到处写字符串 key + 默认值）；
 * - 在内部统一委托给 IConfigManager，集中管理持久化逻辑；
 * - 只暴露真正需要在代码中频繁读取的配置项。
 */
object Prefs {
    private const val CONFIG_PREFS_NAME = "tinaide_config"
    private const val CUSTOM_EDITOR_THEME_KEY = "editor_custom_theme_v1"

    @Volatile
    private var configManagerRef: IConfigManager? = null

    @Volatile
    private var appContextRef: Context? = null

    fun initialize(context: Context, configManager: IConfigManager) {
        if (configManagerRef != null) return
        appContextRef = context.applicationContext
        configManagerRef = configManager
    }

    private val configManager: IConfigManager
        get() = checkNotNull(configManagerRef) {
            "Prefs not initialized. Call Prefs.initialize(context, configManager) in TinaApplication.onCreate()."
        }

    private val appContext: Context
        get() = checkNotNull(appContextRef) {
            "Prefs not initialized. Call Prefs.initialize(context, configManager) in TinaApplication.onCreate()."
        }

    // 应用偏好 SharedPreferences（tinaide_preferences.xml）
    private val sharedPrefs: SharedPreferences by lazy {
        AppPreferences.get(appContext)
    }

    // ========== PRoot / Rootfs 配置 ==========

    var rootfsPath: String
        get() = configManager.get(ConfigKeys.RootfsPath)
        set(value) {
            configManager.set(ConfigKeys.RootfsPath, value)
        }

    // ========== 日志 / 诊断 ==========

    /**
     * 编辑器触摸/滚动诊断日志开关（默认关闭）。
     *
     * 说明：
     * - 开启后会在 Release 版本中额外记录编辑器触摸序列（如 ACTION_CANCEL、UP 速度等），用于定位“滑动卡住/事件被打断”问题；
     * - 日志写入文件，用户导出日志即可提供给开发者；
     * - 如需降低日志量/性能开销，可在设置中关闭。
     */
    var editorTouchDiagnosticsEnabled: Boolean
        get() = sharedPrefs.getBoolean("editor_touch_diagnostics_enabled", false)
        set(value) {
            sharedPrefs.edit().putBoolean("editor_touch_diagnostics_enabled", value).apply()
            notifyDeveloperDiagnosticsSettingsChanged()
        }

    /**
     * 诊断日志总开关（开发者选项）。
     *
     * - 用于统一关闭/开启所有“为了排查问题而加的日志”，避免影响性能或造成日志量过大；
     * - 仅开发者选项页面提供入口。
     */
    var devDiagnosticsEnabled: Boolean
        get() = sharedPrefs.getBoolean("dev_diagnostics_enabled", false)
        set(value) {
            sharedPrefs.edit().putBoolean("dev_diagnostics_enabled", value).apply()
            notifyDeveloperDiagnosticsSettingsChanged()
        }

    /** 记录构建保存、目标选择、增量缓存、compile_commands 与启动产物摘要日志（开发者选项）。 */
    var devBuildDiagnosticsLogEnabled: Boolean
        get() = sharedPrefs.getBoolean("dev_build_diagnostics_log_enabled", false)
        set(value) {
            sharedPrefs.edit().putBoolean("dev_build_diagnostics_log_enabled", value).apply()
            notifyDeveloperDiagnosticsSettingsChanged()
        }

    /** 记录 compile_commands 选择/生成/复用摘要日志（开发者选项）。 */
    var devLspCompileCommandsSelectionLogEnabled: Boolean
        get() = sharedPrefs.getBoolean("dev_lsp_compile_commands_selection_log_enabled", false)
        set(value) {
            sharedPrefs.edit().putBoolean("dev_lsp_compile_commands_selection_log_enabled", value).apply()
            notifyDeveloperDiagnosticsSettingsChanged()
        }

    /** 记录 clangd 启动前读取的编译数据库摘要日志（开发者选项）。 */
    var devLspClangdStartupLogEnabled: Boolean
        get() = sharedPrefs.getBoolean("dev_lsp_clangd_startup_log_enabled", false)
        set(value) {
            sharedPrefs.edit().putBoolean("dev_lsp_clangd_startup_log_enabled", value).apply()
            notifyDeveloperDiagnosticsSettingsChanged()
        }

    /** 编辑器 LSP 总开关（开发者测试用）。 */
    var devEditorLspEnabled: Boolean
        get() = sharedPrefs.getBoolean("dev_editor_lsp_enabled", true)
        set(value) {
            sharedPrefs.edit().putBoolean("dev_editor_lsp_enabled", value).apply()
            notifyDevEditorLspEnabledChanged()
        }

    /** 内置 CMake LSP 开关（开发者测试用）。 */
    var devBuiltinCmakeLspEnabled: Boolean
        get() = sharedPrefs.getBoolean("dev_builtin_cmake_lsp_enabled", true)
        set(value) {
            sharedPrefs.edit().putBoolean("dev_builtin_cmake_lsp_enabled", value).apply()
            notifyDevBuiltinCmakeLspEnabledChanged()
        }

    /** 记录 Compose 手势链路（Drawer/底部面板等）的诊断日志（开发者选项）。 */
    var devGestureTraceEnabled: Boolean
        get() = sharedPrefs.getBoolean("dev_gesture_trace_enabled", false)
        set(value) {
            sharedPrefs.edit().putBoolean("dev_gesture_trace_enabled", value).apply()
            notifyDeveloperDiagnosticsSettingsChanged()
        }

    /** 记录编辑器内部 fling/触摸处理日志（开发者选项）。 */
    var devEditorTouchInternalLogEnabled: Boolean
        get() = sharedPrefs.getBoolean("dev_editor_touch_internal_log_enabled", false)
        set(value) {
            sharedPrefs.edit().putBoolean("dev_editor_touch_internal_log_enabled", value).apply()
            notifyDeveloperDiagnosticsSettingsChanged()
        }

    /** 记录编辑器双指缩放锚定/漂移明细日志（开发者选项）。 */
    var devEditorTouchScaleLogEnabled: Boolean
        get() = sharedPrefs.getBoolean("dev_editor_touch_scale_log_enabled", false)
        set(value) {
            sharedPrefs.edit().putBoolean("dev_editor_touch_scale_log_enabled", value).apply()
            notifyDeveloperDiagnosticsSettingsChanged()
        }

    /** 记录编辑器双指焦点采样日志（开发者选项）。 */
    var devEditorTouchFocusLogEnabled: Boolean
        get() = sharedPrefs.getBoolean("dev_editor_touch_focus_log_enabled", false)
        set(value) {
            sharedPrefs.edit().putBoolean("dev_editor_touch_focus_log_enabled", value).apply()
            notifyDeveloperDiagnosticsSettingsChanged()
        }

    /** 记录编辑器滚动增量诊断日志（开发者选项）。 */
    var devEditorTouchScrollLogEnabled: Boolean
        get() = sharedPrefs.getBoolean("dev_editor_touch_scroll_log_enabled", false)
        set(value) {
            sharedPrefs.edit().putBoolean("dev_editor_touch_scroll_log_enabled", value).apply()
            notifyDeveloperDiagnosticsSettingsChanged()
        }

    /** 记录编辑器 fling 生命周期日志（开发者选项）。 */
    var devEditorTouchFlingLogEnabled: Boolean
        get() = sharedPrefs.getBoolean("dev_editor_touch_fling_log_enabled", false)
        set(value) {
            sharedPrefs.edit().putBoolean("dev_editor_touch_fling_log_enabled", value).apply()
            notifyDeveloperDiagnosticsSettingsChanged()
        }

    /**
     * TinaIDE 主进程崩溃日志自动上传开关（默认开启）。
     *
     * 说明：崩溃日志由 xCrash 生成，内容包含设备信息、应用信息与堆栈。
     * 用户 native runtime 崩溃仍只保存在本地，不会自动上传。
     */
    var crashAutoUploadEnabled: Boolean
        get() = configManager.get(ConfigKeys.CrashAutoUploadEnabled)
        set(value) {
            configManager.set(ConfigKeys.CrashAutoUploadEnabled, value)
        }

    /** 最近一次已上报的 tombstone 文件名（用于去重）。 */
    var crashLastUploadedTombstoneName: String
        get() = configManager.get(ConfigKeys.CrashLastUploadedTombstoneName)
        set(value) {
            configManager.set(ConfigKeys.CrashLastUploadedTombstoneName, value)
        }

    /** 最近一次已上报的 tombstone 文件修改时间（用于去重）。 */
    var crashLastUploadedTombstoneMtime: Long
        get() = configManager.get(ConfigKeys.CrashLastUploadedTombstoneMtime)
        set(value) {
            configManager.set(ConfigKeys.CrashLastUploadedTombstoneMtime, value)
        }

    // ========== UI / 主题相关 ==========

    /**
     * 应用主题："DARK" / "LIGHT" / "GRAY" / "SAKURA" / "OCEAN" / "SPRING" /
     * "AUTUMN" / "BLACK" / "AUTO"
     * 通过 ThemeManager 统一管理，确保响应式更新
     */
    var appTheme: AppTheme
        get() = ThemeManager.getCurrentTheme()
        set(value) {
            // 更新 ThemeManager（会触发所有订阅者）
            ThemeManager.setTheme(value)
            // 持久化到 SharedPreferences
            configManager.set(ConfigKeys.Theme, value)
        }

    fun readPersistedTheme(context: Context): AppTheme {
        val prefs = context.applicationContext
            .getSharedPreferences(CONFIG_PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(ConfigKeys.Theme.key, null)
            ?.let { storedName -> runCatching { AppTheme.valueOf(storedName) }.getOrNull() }
            ?: ConfigKeys.Theme.default
    }

    fun resolveNightMode(theme: AppTheme): Int = when (theme) {
        AppTheme.DARK, AppTheme.GRAY, AppTheme.BLACK -> AppCompatDelegate.MODE_NIGHT_YES
        AppTheme.AUTO -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        else -> AppCompatDelegate.MODE_NIGHT_NO
    }

    fun applyNightMode(theme: AppTheme) {
        val mode = resolveNightMode(theme)
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }

    val useDarkMode: Boolean
        get() = when (appTheme) {
            AppTheme.DARK, AppTheme.GRAY, AppTheme.BLACK -> true
            AppTheme.AUTO -> {
                val nightModeFlags = appContext.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK
                nightModeFlags == Configuration.UI_MODE_NIGHT_YES
            }
            else -> false
        }

    /**
     * 应用系统级别的夜间模式设置。
     * 在 Activity.onCreate() 的 super.onCreate() 之前调用，确保主题正确应用。
     */
    fun applyTheme() {
        applyNightMode(appTheme)
    }

    // ========== 编辑器配置 ==========

    /**
     * 编辑器设置 StateFlow，用于响应式更新已打开的编辑器
     */
    val editorSettingsFlow: StateFlow<EditorSettings>
        get() = editorSettingsState.asStateFlow()

    private val editorSettingsState: MutableStateFlow<EditorSettings> by lazy {
        MutableStateFlow(readEditorSettings())
    }

    /**
     * 编辑器主题（配色方案）StateFlow，用于即时刷新已打开的编辑器主题。
     *
     * 注意：这是“编辑器主题”，与 AppTheme（整体应用主题）不同。
     */
    val editorThemeFlow: StateFlow<String>
        get() = editorThemeState.asStateFlow()

    private val editorThemeState: MutableStateFlow<String> by lazy {
        MutableStateFlow(editorTheme)
    }

    /** User-defined editor palette, observed separately so edits refresh an active custom theme. */
    val customEditorThemeFlow: StateFlow<CustomEditorTheme>
        get() = customEditorThemeState.asStateFlow()

    private val customEditorThemeState: MutableStateFlow<CustomEditorTheme> by lazy {
        MutableStateFlow(customEditorTheme)
    }

    /**
     * LSP 辅助能力设置 StateFlow，用于响应式更新已打开的编辑器。
     */
    val lspAssistSettingsFlow: StateFlow<LspAssistSettings>
        get() = lspAssistSettingsState.asStateFlow()

    private val lspAssistSettingsState: MutableStateFlow<LspAssistSettings> by lazy {
        MutableStateFlow(readLspAssistSettings())
    }

    /**
     * LSP Folding Range 开关 StateFlow，用于即时刷新折叠数据源。
     */
    val lspFoldingRangeEnabledFlow: StateFlow<Boolean>
        get() = lspFoldingRangeEnabledState.asStateFlow()

    private val lspFoldingRangeEnabledState: MutableStateFlow<Boolean> by lazy {
        MutableStateFlow(lspFoldingRangeEnabled)
    }

    /**
     * Clangd 设置 StateFlow，用于配置 clangd LSP 服务器参数。
     * 修改后需要重新连接 LSP 才能生效。
     */
    val clangdSettingsFlow: StateFlow<ClangdSettings>
        get() = clangdSettingsState.asStateFlow()

    private val clangdSettingsState: MutableStateFlow<ClangdSettings> by lazy {
        MutableStateFlow(readClangdSettings())
    }

    /** 开发者诊断日志设置 StateFlow，用于开发者选项页实时同步。 */
    val devDiagnosticsSettingsFlow: StateFlow<DeveloperDiagnosticsSettings>
        get() = devDiagnosticsSettingsState.asStateFlow()

    private val devDiagnosticsSettingsState: MutableStateFlow<DeveloperDiagnosticsSettings> by lazy {
        MutableStateFlow(readDeveloperDiagnosticsSettings())
    }

    /** 编辑器 LSP 总开关 StateFlow，用于测试页/开发者选项即时切换。 */
    val devEditorLspEnabledFlow: StateFlow<Boolean>
        get() = devEditorLspEnabledState.asStateFlow()

    private val devEditorLspEnabledState: MutableStateFlow<Boolean> by lazy {
        MutableStateFlow(devEditorLspEnabled)
    }

    /** 内置 CMake LSP 开关 StateFlow，用于测试页/开发者选项即时切换。 */
    val devBuiltinCmakeLspEnabledFlow: StateFlow<Boolean>
        get() = devBuiltinCmakeLspEnabledState.asStateFlow()

    private val devBuiltinCmakeLspEnabledState: MutableStateFlow<Boolean> by lazy {
        MutableStateFlow(devBuiltinCmakeLspEnabled)
    }

    private fun readEditorSettings(): EditorSettings = EditorSettings(
        fontSize = editorFontSize,
        tabSize = editorTabSize,
        wordWrap = editorWordWrap,
        showLineNumbers = editorShowLineNumbers,
        autoIndent = editorAutoIndent,
        rainbowBrackets = editorRainbowBrackets,
        rainbowBracketsMaxLines = editorRainbowBracketsMaxLines,
        fontPath = editorFontPath,
        codeFolding = editorCodeFolding,
        renderWhitespace = editorRenderWhitespace,
        insertSpacesForTabs = editorInsertSpacesForTabs,
        scrollFlingEnabled = editorScrollFlingEnabled,
        singleDirectionDragging = editorSingleDirectionDragging,
        singleDirectionFling = editorSingleDirectionFling
    )

    private fun readLspAssistSettings(): LspAssistSettings = LspAssistSettings(
        signatureHelpEnabled = lspSignatureHelpEnabled,
        inlayHintsEnabled = lspInlayHintsEnabled,
        semanticTokensEnabled = lspSemanticTokensEnabled,
    )

    private fun readClangdSettings(): ClangdSettings = ClangdSettings(
        backgroundIndex = clangdBackgroundIndex,
        clangTidy = clangdClangTidy,
        headerInsertion = ClangdSettings.HeaderInsertionMode.fromValue(clangdHeaderInsertion),
        completionStyle = ClangdSettings.CompletionStyle.fromValue(clangdCompletionStyle),
        functionArgPlaceholders = clangdFunctionArgPlaceholders,
    )

    private fun notifyClangdSettingsChanged() {
        clangdSettingsState.value = readClangdSettings()
    }

    private fun readDeveloperDiagnosticsSettings(): DeveloperDiagnosticsSettings = DeveloperDiagnosticsSettings(
        diagnosticsEnabled = devDiagnosticsEnabled,
        buildDiagnosticsLogEnabled = devBuildDiagnosticsLogEnabled,
        lspCompileCommandsSelectionLogEnabled = devLspCompileCommandsSelectionLogEnabled,
        lspClangdStartupLogEnabled = devLspClangdStartupLogEnabled,
        editorTouchDiagnosticsEnabled = editorTouchDiagnosticsEnabled,
        gestureTraceEnabled = devGestureTraceEnabled,
        editorInternalTouchLogEnabled = devEditorTouchInternalLogEnabled,
        editorScaleLogEnabled = devEditorTouchScaleLogEnabled,
        editorFocusLogEnabled = devEditorTouchFocusLogEnabled,
        editorScrollLogEnabled = devEditorTouchScrollLogEnabled,
        editorFlingLogEnabled = devEditorTouchFlingLogEnabled
    )

    private fun notifyDeveloperDiagnosticsSettingsChanged() {
        devDiagnosticsSettingsState.value = readDeveloperDiagnosticsSettings()
    }

    private fun notifyDevEditorLspEnabledChanged() {
        devEditorLspEnabledState.value = devEditorLspEnabled
    }

    private fun notifyDevBuiltinCmakeLspEnabledChanged() {
        devBuiltinCmakeLspEnabledState.value = devBuiltinCmakeLspEnabled
    }

    private fun notifyLspAssistSettingsChanged() {
        lspAssistSettingsState.value = readLspAssistSettings()
    }

    private fun notifyLspFoldingRangeEnabledChanged() {
        lspFoldingRangeEnabledState.value = lspFoldingRangeEnabled
    }

    private fun notifyEditorSettingsChanged() {
        editorSettingsState.value = readEditorSettings()
    }

    /**
     * 编辑器字体大小（sp）。
     * 使用 AppFontManager 中定义的统一常量。
     * 存储于默认 SharedPreferences 中，键为 "editor_font_size"。
     * 注意：使用 String 存储以兼容 EditTextPreference。
     */
    val editorFontSize: Float
        get() = sharedPrefs.getString("editor_font_size", AppFontManager.DEFAULT_EDITOR_FONT_SIZE.toInt().toString())
            ?.toFloatOrNull()
            ?.let { AppFontManager.clampFontSize(it) }
            ?: AppFontManager.DEFAULT_EDITOR_FONT_SIZE

    /**
     * Tab 宽度（空格数）。默认 4，范围 [2, 8]。
     * 存储为字符串，键为 "editor_tab_size"。
     */
    val editorTabSize: Int
        get() = sharedPrefs.getString("editor_tab_size", "4")
            ?.toIntOrNull()
            ?.coerceIn(2, 8) ?: 4

    /** 是否启用自动换行。 */
    val editorWordWrap: Boolean
        get() = sharedPrefs.getBoolean("editor_word_wrap", false)

    /** 是否显示行号。 */
    val editorShowLineNumbers: Boolean
        get() = sharedPrefs.getBoolean("editor_line_numbers", true)

    /** 是否启用自动缩进。 */
    val editorAutoIndent: Boolean
        get() = sharedPrefs.getBoolean("editor_auto_indent", true)

    /**
     * 自定义字体路径。空字符串表示使用默认字体。
     * 存储于默认 SharedPreferences 中，键为 "editor_font_path"。
     */
    val editorFontPath: String
        get() = sharedPrefs.getString("editor_font_path", "") ?: ""

    /**
     * 编辑器主题：内置主题、CUSTOM，或 plugin: 前缀的插件主题 ID。
     * 默认 GRAY（灰色主题）
     */
    val editorTheme: String
        get() = sharedPrefs.getString("editor_theme", "GRAY") ?: "GRAY"

    val customEditorTheme: CustomEditorTheme
        get() = CustomEditorThemeCodec.decode(sharedPrefs.getString(CUSTOM_EDITOR_THEME_KEY, null))

    /**
     * 是否启用彩虹括号。
     * 彩虹括号根据嵌套深度为括号着色，方便识别匹配的括号对。
     */
    val editorRainbowBrackets: Boolean
        get() = sharedPrefs.getBoolean("editor_rainbow_brackets", true)

    /**
     * 代码格式化默认风格。
     * 当项目中没有 .clang-format 文件时使用此风格。
     * 可选值：LLVM, Google, Chromium, Mozilla, WebKit, Microsoft, GNU
     * 默认为 LLVM
     */
    val codeFormatStyle: String
        get() = sharedPrefs.getString("code_format_style", "LLVM") ?: "LLVM"

    /**
     * 彩虹括号自动禁用阈值（行数）。
     *
     * - 0：不限制（永不因行数自动禁用）
     * - >0：当文档总行数大于该值时，自动禁用彩虹括号以避免大文件卡顿
     */
    val editorRainbowBracketsMaxLines: Int
        get() = sharedPrefs.getInt("editor_rainbow_brackets_max_lines", 5000).coerceIn(0, 200_000)

    /**
     * 是否启用代码折叠。
     * 代码折叠允许折叠/展开代码块（如函数、类、条件语句等）。
     */
    val editorCodeFolding: Boolean
        get() = sharedPrefs.getBoolean("editor_code_folding", true)

    /**
     * 空白字符可视化模式：
     * - "none"：不显示
     * - "boundary"：仅显示行首/行尾的空白
     * - "all"：显示所有空白字符
     */
    val editorRenderWhitespace: String
        get() = sharedPrefs.getString("editor_render_whitespace", "none") ?: "none"

    /**
     * 是否将 Tab 转换为空格插入。
     * 默认开启（与大多数现代编辑器一致）。
     */
    val editorInsertSpacesForTabs: Boolean
        get() = sharedPrefs.getBoolean("editor_insert_spaces_for_tabs", true)

    val editorScrollFlingEnabled: Boolean
        get() = sharedPrefs.getBoolean("editor_scroll_fling_enabled", true)

    val editorSingleDirectionDragging: Boolean
        get() = sharedPrefs.getBoolean("editor_single_direction_dragging", true)

    val editorSingleDirectionFling: Boolean
        get() = sharedPrefs.getBoolean("editor_single_direction_fling", true)

    /**
     * 编辑器硬件加速开关。
     *
     * 说明：
     * - 开启后使用 GPU 渲染，滚动性能更好（默认开启）
     * - 某些设备（如一加）可能在硬件加速时出现 Surface 缓冲区错误
     * - 如遇到编辑器滚动卡顿、黑屏或日志中出现 IGraphicBufferProducer 错误，可尝试关闭
     * - 关闭后使用系统默认渲染模式，兼容性更好但性能可能略有下降
     */
    val editorHardwareAcceleration: Boolean
        get() = sharedPrefs.getBoolean("editor_hardware_acceleration", true)

    // ========== 编译器配置（按需扩展） ==========

    /** 编译优化等级，例如 "O0" / "O2" 等。 */
    val compilerOptimizationLevel: String
        get() = sharedPrefs.getString("compiler_optimization", "O2") ?: "O2"

    /** 编译线程数，默认 2，范围 [1, 8]。 */
    val compilerThreads: Int
        get() = sharedPrefs.getInt("compiler_threads", 2).coerceIn(1, 8)

    /** LSP 补全候选数量限制，默认 50，范围 [10, 200]。 */
    val lspCompletionLimit: Int
        get() = sharedPrefs.getInt("lsp_completion_limit", 50).coerceIn(10, 200)

    /**
     * 补全大小写敏感开关。
     *
     * - false：大小写不敏感（更“宽松”，更像“模糊前缀”）
     * - true：大小写敏感（更“严格”，类似 CLion：大小写不匹配则不显示该项）
     */
    val completionCaseSensitive: Boolean
        get() = sharedPrefs.getBoolean("completion_case_sensitive", false)

    /**
     * LSP 签名帮助（参数提示窗口）开关。
     *
     * 默认开启：更接近 CLion 的调用参数提示体验。
     */
    val lspSignatureHelpEnabled: Boolean
        get() = sharedPrefs.getBoolean("lsp_signature_help_enabled", true)

    /**
     * LSP Inlay Hints（行内提示，如参数名提示）开关。
     *
     * 默认开启：配合“智能（CLion 风格）”的函数补全，可在不插入参数名的情况下显示提示。
     */
    val lspInlayHintsEnabled: Boolean
        get() = sharedPrefs.getBoolean("lsp_inlay_hints_enabled", true)

    /**
     * LSP Semantic Tokens（语义高亮）开关。
     *
     * 默认开启：优先使用 Language Server 的语义 token 覆盖编辑器语法高亮（更准确）。
     */
    val lspSemanticTokensEnabled: Boolean
        get() = sharedPrefs.getBoolean("lsp_semantic_tokens_enabled", true)

    /**
     * LSP Folding Range（折叠范围）开关。
     *
     * 开启后优先使用 language server 的 foldingRange 结果作为折叠数据源。
     * 默认关闭：避免在部分服务器/超大文件上带来额外请求开销。
     */
    val lspFoldingRangeEnabled: Boolean
        get() = sharedPrefs.getBoolean("lsp_folding_range_enabled", false)

    // ========== Clangd 配置 ==========

    /**
     * Clangd 运行模式。
     * - "native"：原生模式，通过 linker64 直接启动 clangd（推荐，性能更好）
     * - "proot"：PRoot 模式，在 PRoot 环境中启动 clangd（兼容性更好）
     * 默认 "native"。
     */
    val clangdRunMode: String
        get() = sharedPrefs.getString("clangd_run_mode", "native") ?: "native"

    /**
     * 是否启用 clangd 后台索引。
     * 后台索引会分析项目中的所有文件，提供更准确的跨文件补全和跳转。
     * 默认开启，低端设备可关闭以节省资源。
     */
    val clangdBackgroundIndex: Boolean
        get() = sharedPrefs.getBoolean("clangd_background_index", true)

    /**
     * 是否启用 clang-tidy 静态分析。
     * 启用后会在编辑器中显示代码质量警告和建议。
     * 默认开启。
     */
    val clangdClangTidy: Boolean
        get() = sharedPrefs.getBoolean("clangd_clang_tidy", true)

    /**
     * 头文件插入模式。
     * - "never"：永不自动插入
     * - "iwyu"：Include What You Use，智能插入所需头文件
     * 默认 "iwyu"。
     */
    val clangdHeaderInsertion: String
        get() = sharedPrefs.getString("clangd_header_insertion", "iwyu") ?: "iwyu"

    /**
     * 补全样式。
     * - "detailed"：详细模式，显示完整的类型信息
     * - "bundled"：简洁模式，仅显示基本信息
     * 默认 "detailed"。
     */
    val clangdCompletionStyle: String
        get() = sharedPrefs.getString("clangd_completion_style", "detailed") ?: "detailed"

    /**
     * 是否启用函数参数占位符。
     * 启用后，补全函数时会插入参数占位符，方便快速填写。
     * 默认开启。
     */
    val clangdFunctionArgPlaceholders: Boolean
        get() = sharedPrefs.getBoolean("clangd_function_arg_placeholders", true)

    // ========== CMake 配置 ==========

    /**
     * CMake 运行模式。
     * - "native"：原生模式，通过 linker64 直接运行 cmake（推荐，性能更好）
     * - "proot"：PRoot 模式，在 PRoot 环境中运行 cmake（兼容性更好）
     * 默认 "native"。
     */
    val cmakeRunMode: String
        get() = sharedPrefs.getString("cmake_run_mode", "native") ?: "native"

    /** CMake 构建类型："Debug" / "Release" / "RelWithDebInfo" / "MinSizeRel"。 */
    val cmakeBuildType: String
        get() = sharedPrefs.getString("cmake_build_type", "Debug") ?: "Debug"

    /** CMake 生成器："Unix Makefiles" / "Ninja"。 */
    val cmakeGenerator: String
        get() = sharedPrefs.getString("cmake_generator", "Ninja") ?: "Ninja"

    /** CMake 并行任务数，默认 4，范围 [1, 8]。 */
    val cmakeParallelJobs: Int
        get() = sharedPrefs.getInt("cmake_parallel_jobs", 4).coerceIn(1, 8)

    /**
     * clang-format 运行模式：
     * - "native"：原生模式，通过 linker64 直接运行 clang-format（推荐，性能更好）
     * - "proot"：PRoot 模式，在 PRoot 环境中运行 clang-format（兼容性更好）
     * 默认 "native"。
     */
    val clangFormatRunMode: String
        get() = sharedPrefs.getString("clang_format_run_mode", "native") ?: "native"

    /**
     * Make 运行模式：
     * - "native"：原生模式，通过 linker64 直接运行 make（推荐，性能更好）
     * - "proot"：PRoot 模式，在 PRoot 环境中运行 make（兼容性更好）
     * 默认 "native"。
     */
    val makeRunMode: String
        get() = sharedPrefs.getString("make_run_mode", "native") ?: "native"

    // ========== 项目配置 ==========

    /** 新建项目默认源码存储位置。 */
    val projectDefaultSourceLocation: NewProjectSourceLocation
        get() = NewProjectSourceLocation.fromValue(
            sharedPrefs.getString(
                "project_default_source_location",
                NewProjectSourceLocation.PUBLIC.value
            )
        )

    /** 自动保存间隔（秒），0 表示关闭。 */
    val projectAutoSaveInterval: Int
        get() = sharedPrefs.getString("project_auto_save", "60")?.toIntOrNull() ?: 60

    /** 是否启用自动备份。 */
    val projectAutoBackup: Boolean
        get() = sharedPrefs.getBoolean("project_backup", true)

    // ========== 开发者选项 ==========

    /**
     * 开发者选项是否已启用
     * 通过在"关于"页面点击版本号 5 次来启用
     */
    val developerOptionsEnabled: Boolean
        get() = sharedPrefs.getBoolean("developer_options_enabled", false)

    /** 开发者选项启用状态的 StateFlow */
    val developerOptionsEnabledFlow: StateFlow<Boolean>
        get() = developerOptionsEnabledState.asStateFlow()

    private val developerOptionsEnabledState: MutableStateFlow<Boolean> by lazy {
        MutableStateFlow(developerOptionsEnabled)
    }

    // ========== 调试配置 ==========

    /**
     * 调试工具栏位置："top" / "bottom" / "both"。
     *
     * - 统一持久化到 ConfigManager（便于导出/导入与集中管理）
     * - 通过 StateFlow 暴露给 Compose，确保设置变更可实时生效
     */
    val debugToolbarPosition: DebugToolbarPosition
        get() = debugToolbarPositionState.value

    val debugToolbarPositionFlow: StateFlow<DebugToolbarPosition>
        get() = debugToolbarPositionState.asStateFlow()

    private val debugToolbarPositionState: MutableStateFlow<DebugToolbarPosition> by lazy {
        val state = MutableStateFlow(readDebugToolbarPositionFromConfig())
        configManager.addListener(
            ConfigKeys.DebugToolbarPosition.key,
            object : ConfigChangeListener {
                override fun onConfigChanged(key: String, newValue: Any?) {
                    state.value = readDebugToolbarPositionFromConfig()
                }
            }
        )
        state
    }

    // ========== 简单写入方法（供设置界面或业务调用） ==========

    fun setTheme(theme: AppTheme) {
        appTheme = theme // 使用 setter，会自动触发 ThemeManager
    }

    fun setEditorFontSize(sizeSp: Float) {
        val clamped = AppFontManager.clampFontSize(sizeSp)
        sharedPrefs.edit().putString("editor_font_size", clamped.toInt().toString()).apply()
        notifyEditorSettingsChanged()
    }

    fun setEditorTabSize(tabSize: Int) {
        sharedPrefs.edit().putString("editor_tab_size", tabSize.coerceIn(2, 8).toString()).apply()
        notifyEditorSettingsChanged()
    }

    fun setEditorWordWrap(enabled: Boolean) {
        sharedPrefs.edit().putBoolean("editor_word_wrap", enabled).apply()
        notifyEditorSettingsChanged()
    }

    fun setEditorShowLineNumbers(enabled: Boolean) {
        sharedPrefs.edit().putBoolean("editor_line_numbers", enabled).apply()
        notifyEditorSettingsChanged()
    }

    fun setEditorAutoIndent(enabled: Boolean) {
        sharedPrefs.edit().putBoolean("editor_auto_indent", enabled).apply()
        notifyEditorSettingsChanged()
    }

    fun setEditorFontPath(path: String) {
        sharedPrefs.edit().putString("editor_font_path", path).apply()
        notifyEditorSettingsChanged()
    }

    fun setEditorTheme(theme: String) {
        sharedPrefs.edit().putString("editor_theme", theme).apply()
        editorThemeState.value = theme
    }

    fun setCustomEditorTheme(theme: CustomEditorTheme) {
        val sanitized = theme.sanitized()
        sharedPrefs.edit()
            .putString(CUSTOM_EDITOR_THEME_KEY, CustomEditorThemeCodec.encode(sanitized))
            .apply()
        customEditorThemeState.value = sanitized
    }

    fun resetCustomEditorTheme(base: EditorThemeBase = EditorThemeBase.GRAY) {
        setCustomEditorTheme(CustomEditorTheme(base = base))
    }

    fun setEditorRainbowBrackets(enabled: Boolean) {
        sharedPrefs.edit().putBoolean("editor_rainbow_brackets", enabled).apply()
        notifyEditorSettingsChanged()
    }

    fun setEditorRainbowBracketsMaxLines(maxLines: Int) {
        sharedPrefs.edit().putInt("editor_rainbow_brackets_max_lines", maxLines.coerceIn(0, 200_000)).apply()
        notifyEditorSettingsChanged()
    }

    fun setEditorCodeFolding(enabled: Boolean) {
        sharedPrefs.edit().putBoolean("editor_code_folding", enabled).apply()
        notifyEditorSettingsChanged()
    }

    fun setEditorRenderWhitespace(mode: String) {
        val valid = when (mode) {
            "none", "boundary", "all" -> mode
            else -> "none"
        }
        sharedPrefs.edit().putString("editor_render_whitespace", valid).apply()
        notifyEditorSettingsChanged()
    }

    fun setEditorInsertSpacesForTabs(enabled: Boolean) {
        sharedPrefs.edit().putBoolean("editor_insert_spaces_for_tabs", enabled).apply()
        notifyEditorSettingsChanged()
    }

    fun setEditorScrollFlingEnabled(enabled: Boolean) {
        sharedPrefs.edit().putBoolean("editor_scroll_fling_enabled", enabled).apply()
        notifyEditorSettingsChanged()
    }

    fun setEditorSingleDirectionDragging(enabled: Boolean) {
        sharedPrefs.edit().putBoolean("editor_single_direction_dragging", enabled).apply()
        notifyEditorSettingsChanged()
    }

    fun setEditorSingleDirectionFling(enabled: Boolean) {
        sharedPrefs.edit().putBoolean("editor_single_direction_fling", enabled).apply()
        notifyEditorSettingsChanged()
    }

    /**
     * 设置编辑器硬件加速开关
     * 注意：修改后需要重新打开文件才能生效
     */
    fun setEditorHardwareAcceleration(enabled: Boolean) {
        sharedPrefs.edit().putBoolean("editor_hardware_acceleration", enabled).apply()
    }

    /**
     * 设置代码格式化默认风格
     */
    fun setCodeFormatStyle(style: String) {
        sharedPrefs.edit().putString("code_format_style", style).apply()
    }

    // ========== 编译器配置写入 ==========

    fun setCompilerOptimizationLevel(level: String) {
        sharedPrefs.edit().putString("compiler_optimization", level).apply()
    }

    fun setCompilerThreads(threads: Int) {
        sharedPrefs.edit().putInt("compiler_threads", threads.coerceIn(1, 8)).apply()
    }

    fun setLspCompletionLimit(limit: Int) {
        sharedPrefs.edit().putInt("lsp_completion_limit", limit.coerceIn(10, 200)).apply()
    }

    fun setCompletionCaseSensitive(enabled: Boolean) {
        sharedPrefs.edit().putBoolean("completion_case_sensitive", enabled).apply()
    }

    fun setLspSignatureHelpEnabled(enabled: Boolean) {
        sharedPrefs.edit().putBoolean("lsp_signature_help_enabled", enabled).apply()
        notifyLspAssistSettingsChanged()
    }

    fun setLspInlayHintsEnabled(enabled: Boolean) {
        sharedPrefs.edit().putBoolean("lsp_inlay_hints_enabled", enabled).apply()
        notifyLspAssistSettingsChanged()
    }

    fun setLspSemanticTokensEnabled(enabled: Boolean) {
        sharedPrefs.edit().putBoolean("lsp_semantic_tokens_enabled", enabled).apply()
        notifyLspAssistSettingsChanged()
    }

    fun setLspFoldingRangeEnabled(enabled: Boolean) {
        sharedPrefs.edit().putBoolean("lsp_folding_range_enabled", enabled).apply()
        notifyLspFoldingRangeEnabledChanged()
    }

    // ========== Clangd 配置写入 ==========

    /**
     * 设置 clangd 运行模式
     * 注意：修改后需要重新连接 LSP 才能生效
     */
    fun setClangdRunMode(mode: String) {
        sharedPrefs.edit().putString("clangd_run_mode", mode).apply()
        notifyClangdSettingsChanged()
    }

    /**
     * 设置 clangd 后台索引开关
     * 注意：修改后需要重新连接 LSP 才能生效
     */
    fun setClangdBackgroundIndex(enabled: Boolean) {
        sharedPrefs.edit().putBoolean("clangd_background_index", enabled).apply()
        notifyClangdSettingsChanged()
    }

    /**
     * 设置 clang-tidy 静态分析开关
     * 注意：修改后需要重新连接 LSP 才能生效
     */
    fun setClangdClangTidy(enabled: Boolean) {
        sharedPrefs.edit().putBoolean("clangd_clang_tidy", enabled).apply()
        notifyClangdSettingsChanged()
    }

    /**
     * 设置头文件插入模式
     * 注意：修改后需要重新连接 LSP 才能生效
     */
    fun setClangdHeaderInsertion(mode: String) {
        sharedPrefs.edit().putString("clangd_header_insertion", mode).apply()
        notifyClangdSettingsChanged()
    }

    /**
     * 设置补全样式
     * 注意：修改后需要重新连接 LSP 才能生效
     */
    fun setClangdCompletionStyle(style: String) {
        sharedPrefs.edit().putString("clangd_completion_style", style).apply()
        notifyClangdSettingsChanged()
    }

    /**
     * 设置函数参数占位符开关
     * 注意：修改后需要重新连接 LSP 才能生效
     */
    fun setClangdFunctionArgPlaceholders(enabled: Boolean) {
        sharedPrefs.edit().putBoolean("clangd_function_arg_placeholders", enabled).apply()
        notifyClangdSettingsChanged()
    }

    // ========== CMake 配置写入 ==========

    /**
     * 设置 CMake 运行模式
     * 注意：修改后需要重新编译项目才能生效
     */
    fun setCmakeRunMode(mode: String) {
        sharedPrefs.edit().putString("cmake_run_mode", mode).apply()
    }

    /**
     * 设置 clang-format 运行模式
     * @param mode "native" 或 "proot"
     */
    fun setClangFormatRunMode(mode: String) {
        sharedPrefs.edit().putString("clang_format_run_mode", mode).apply()
    }

    /**
     * 设置 Make 运行模式
     * @param mode "native" 或 "proot"
     */
    fun setMakeRunMode(mode: String) {
        sharedPrefs.edit().putString("make_run_mode", mode).apply()
    }

    fun setCmakeBuildType(buildType: String) {
        sharedPrefs.edit().putString("cmake_build_type", buildType).apply()
    }

    fun setCmakeGenerator(generator: String) {
        sharedPrefs.edit().putString("cmake_generator", generator).apply()
    }

    fun setCmakeParallelJobs(jobs: Int) {
        sharedPrefs.edit().putInt("cmake_parallel_jobs", jobs.coerceIn(1, 8)).apply()
    }

    // ========== 项目配置写入 ==========

    fun setProjectDefaultSourceLocation(location: NewProjectSourceLocation) {
        sharedPrefs.edit().putString("project_default_source_location", location.value).apply()
    }

    fun setProjectAutoSaveInterval(seconds: Int) {
        sharedPrefs.edit().putString("project_auto_save", seconds.toString()).apply()
    }

    fun setProjectAutoBackup(enabled: Boolean) {
        sharedPrefs.edit().putBoolean("project_backup", enabled).apply()
    }

    // ========== 开发者选项写入 ==========

    /**
     * 设置开发者选项启用状态
     */
    fun setDeveloperOptionsEnabled(enabled: Boolean) {
        sharedPrefs.edit().putBoolean("developer_options_enabled", enabled).apply()
        developerOptionsEnabledState.value = enabled
    }

    // ========== 调试配置写入 ==========

    fun setDebugToolbarPosition(position: DebugToolbarPosition) {
        configManager.set(ConfigKeys.DebugToolbarPosition, position.value)
    }

    private fun readDebugToolbarPositionFromConfig(): DebugToolbarPosition {
        val value = configManager.get(ConfigKeys.DebugToolbarPosition)
        return DebugToolbarPosition.fromString(value)
    }
}
