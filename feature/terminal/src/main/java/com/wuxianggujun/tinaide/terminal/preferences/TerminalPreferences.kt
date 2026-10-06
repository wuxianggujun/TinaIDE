package com.wuxianggujun.tinaide.terminal.preferences

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import androidx.annotation.StringRes
import com.wuxianggujun.tinaide.core.font.AppFontManager
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.termux.terminal.TerminalSession
import com.wuxianggujun.tinaide.core.terminal.ITerminalPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * 终端设置持久化
 *
 * 管理终端相关的用户偏好设置，包括字体大小、配色方案、字体等。
 */
class TerminalPreferences(private val context: Context) : ITerminalPreferences {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("terminal_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "TerminalPreferences"

        @Volatile
        private var instance: TerminalPreferences? = null

        fun get(context: Context): TerminalPreferences {
            val appContext = context.applicationContext
            return instance ?: synchronized(this) {
                instance ?: TerminalPreferences(appContext).also { instance = it }
            }
        }

        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_THEME_NAME = "theme_name"
        private const val KEY_LOCALE = "locale"
        private const val KEY_FONT_NAME = "font_name"
        private const val KEY_CUSTOM_FONT_PATH = "custom_font_path"
        private const val KEY_CURSOR_BLINK_ENABLED = "cursor_blink_enabled"
        private const val KEY_CURSOR_BLINK_RATE = "cursor_blink_rate"
        private const val KEY_SHELL_TYPE = "shell_type"
        private const val KEY_TERMINAL_BACKEND = "terminal_backend"
        private const val KEY_SHOW_RAW_LINKER_OUTPUT = "show_raw_linker_output"

        // 使用统一的字体常量
        val DEFAULT_FONT_SIZE = AppFontManager.DEFAULT_TERMINAL_FONT_SIZE
        val MIN_FONT_SIZE = AppFontManager.MIN_FONT_SIZE
        val MAX_FONT_SIZE = AppFontManager.TERMINAL_MAX_FONT_SIZE

        const val DEFAULT_THEME = "Default"
        const val DEFAULT_LOCALE = "C.UTF-8"

        // 字体类型常量
        const val FONT_TYPE_BUILTIN = "builtin" // 使用内置字体
        const val FONT_TYPE_SYSTEM = "system" // 使用系统等宽字体
        const val FONT_TYPE_CUSTOM = "custom" // 使用自定义字体

        const val DEFAULT_FONT_NAME = FONT_TYPE_BUILTIN

        // 光标闪烁常量（与 TerminalView 保持一致）
        const val CURSOR_BLINK_RATE_MIN = 100 // 最小闪烁率 100ms
        const val CURSOR_BLINK_RATE_MAX = 2000 // 最大闪烁率 2000ms
        const val DEFAULT_CURSOR_BLINK_RATE = 500 // 默认闪烁率 500ms
        const val DEFAULT_CURSOR_BLINK_ENABLED = false // 默认不启用光标闪烁

        // 默认过滤 linker 兼容告警（false = 不显示原始输出 = 过滤开启）
        const val DEFAULT_SHOW_RAW_LINKER_OUTPUT = false

        // Shell 类型常量
        const val SHELL_TYPE_AUTO = "auto" // 自动检测
        const val SHELL_TYPE_SH = "sh" // Bourne Shell
        const val SHELL_TYPE_BASH = "bash" // Bash
        const val SHELL_TYPE_ZSH = "zsh" // Zsh

        const val DEFAULT_SHELL_TYPE = SHELL_TYPE_AUTO

        // 终端后端模式常量
        const val BACKEND_AUTO = "auto" // 自动（已安装 PRoot 则用 PRoot，否则用 HOST）
        const val BACKEND_PROOT = "proot" // 强制使用 PRoot Linux 环境
        const val BACKEND_HOST = "host" // 强制使用 Android 原生环境

        const val DEFAULT_BACKEND = BACKEND_AUTO
    }

    /**
     * 支持的 locale 选项
     */
    enum class TerminalLocale(val value: String, @param:StringRes @get:StringRes val displayNameResId: Int) {
        C_UTF8("C.UTF-8", Strings.terminal_locale_c_utf8),
        ZH_CN("zh_CN.UTF-8", Strings.terminal_locale_zh_cn),
        ZH_TW("zh_TW.UTF-8", Strings.terminal_locale_zh_tw),
        EN_US("en_US.UTF-8", Strings.terminal_locale_en_us),
        JA_JP("ja_JP.UTF-8", Strings.terminal_locale_ja_jp);

        fun getDisplayName(context: Context): String = context.getString(displayNameResId)

        val displayName: String
            get() = displayNameResId.str()

        companion object {
            fun fromValue(value: String): TerminalLocale = entries.find { it.value == value } ?: C_UTF8
        }
    }

    /**
     * 支持的 Shell 类型
     */
    enum class ShellType(val value: String) {
        AUTO("auto"),
        SH("sh"),
        BASH("bash"),
        ZSH("zsh");

        companion object {
            fun fromValue(value: String): ShellType = entries.find { it.value == value } ?: AUTO
        }
    }

    /**
     * 终端后端模式
     *
     * - AUTO: 自动选择（如果 PRoot 已安装则用 PRoot，否则用 HOST）
     * - PROOT: 强制使用 PRoot Linux 容器环境（完整 Linux 开发环境，apt/gcc/python 等）
     * - HOST: 强制使用 Android 原生环境（可运行 NDK 编译的二进制文件）
     */
    enum class BackendMode(val value: String) {
        AUTO("auto"),
        PROOT("proot"),
        HOST("host");

        companion object {
            fun fromValue(value: String): BackendMode = entries.find { it.value == value } ?: AUTO
        }
    }

    // 字体大小 StateFlow
    private val _fontSize = MutableStateFlow(
        prefs.getFloat(KEY_FONT_SIZE, DEFAULT_FONT_SIZE)
    )
    override val fontSizeFlow: StateFlow<Float> = _fontSize.asStateFlow()

    // 主题名称 StateFlow
    private val _themeName = MutableStateFlow(
        prefs.getString(KEY_THEME_NAME, DEFAULT_THEME) ?: DEFAULT_THEME
    )
    override val themeNameFlow: StateFlow<String> = _themeName.asStateFlow()

    // Locale StateFlow
    private val _locale = MutableStateFlow(
        prefs.getString(KEY_LOCALE, DEFAULT_LOCALE) ?: DEFAULT_LOCALE
    )
    override val localeFlow: StateFlow<String> = _locale.asStateFlow()

    // 字体名称 StateFlow
    private val _fontName = MutableStateFlow(
        prefs.getString(KEY_FONT_NAME, DEFAULT_FONT_NAME) ?: DEFAULT_FONT_NAME
    )
    override val fontNameFlow: StateFlow<String> = _fontName.asStateFlow()

    // 自定义字体路径 StateFlow
    private val _customFontPath = MutableStateFlow(
        prefs.getString(KEY_CUSTOM_FONT_PATH, "") ?: ""
    )
    val customFontPathFlow: StateFlow<String> = _customFontPath.asStateFlow()

    // 光标闪烁启用状态 StateFlow
    private val _cursorBlinkEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_CURSOR_BLINK_ENABLED, DEFAULT_CURSOR_BLINK_ENABLED)
    )
    override val cursorBlinkEnabledFlow: StateFlow<Boolean> = _cursorBlinkEnabled.asStateFlow()

    // 光标闪烁率 StateFlow
    private val _cursorBlinkRate = MutableStateFlow(
        prefs.getInt(KEY_CURSOR_BLINK_RATE, DEFAULT_CURSOR_BLINK_RATE)
    )
    override val cursorBlinkRateFlow: StateFlow<Int> = _cursorBlinkRate.asStateFlow()

    // Shell 类型 StateFlow
    private val _shellType = MutableStateFlow(
        prefs.getString(KEY_SHELL_TYPE, DEFAULT_SHELL_TYPE) ?: DEFAULT_SHELL_TYPE
    )
    override val shellTypeFlow: StateFlow<String> = _shellType.asStateFlow()

    // 终端后端模式 StateFlow
    private val _backendMode = MutableStateFlow(
        prefs.getString(KEY_TERMINAL_BACKEND, DEFAULT_BACKEND) ?: DEFAULT_BACKEND
    )
    override val backendModeFlow: StateFlow<String> = _backendMode.asStateFlow()

    // 是否原样显示 linker 告警（总闸）StateFlow
    private val _showRawLinkerOutput = MutableStateFlow(
        prefs.getBoolean(KEY_SHOW_RAW_LINKER_OUTPUT, DEFAULT_SHOW_RAW_LINKER_OUTPUT)
    )
    override val showRawLinkerOutputFlow: StateFlow<Boolean> = _showRawLinkerOutput.asStateFlow()

    init {
        // 构造即把已持久化的总闸状态推给过滤器，保证 App 冷启动时与设置一致。
        applyLinkerWarningFilterState(_showRawLinkerOutput.value)
    }

    /**
     * 字体大小（sp）
     */
    override var fontSize: Float
        get() = _fontSize.value
        set(value) {
            val clamped = value.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
            prefs.edit().putFloat(KEY_FONT_SIZE, clamped).apply()
            _fontSize.value = clamped
        }

    /**
     * 主题名称
     */
    override var themeName: String
        get() = _themeName.value
        set(value) {
            prefs.edit().putString(KEY_THEME_NAME, value).apply()
            _themeName.value = value
        }

    /**
     * 终端语言环境 (locale)
     *
     * 设置后需要重启终端生效。
     * 注意：使用非 C.UTF-8 的 locale 依赖 guest Linux 环境中的 locale 支持。
     */
    override var locale: String
        get() = _locale.value
        set(value) {
            prefs.edit().putString(KEY_LOCALE, value).apply()
            _locale.value = value
        }

    /**
     * 获取当前 locale 的枚举值
     */
    val terminalLocale: TerminalLocale
        get() = TerminalLocale.fromValue(_locale.value)

    /**
     * Shell 类型
     *
     * 设置后需要重启终端生效。
     */
    override var shellType: String
        get() = _shellType.value
        set(value) {
            prefs.edit().putString(KEY_SHELL_TYPE, value).apply()
            _shellType.value = value
        }

    /**
     * 获取当前 Shell 类型的枚举值
     */
    val terminalShellType: ShellType
        get() = ShellType.fromValue(_shellType.value)

    /**
     * 终端后端模式
     *
     * 设置后需要重启终端生效。
     * - auto: 自动选择（已安装 PRoot 则用 PRoot，否则用 HOST）
     * - proot: 强制使用 PRoot Linux 环境（完整开发环境）
     * - host: 强制使用 Android 原生环境（运行 NDK 编译的程序）
     */
    override var backendMode: String
        get() = _backendMode.value
        set(value) {
            prefs.edit().putString(KEY_TERMINAL_BACKEND, value).apply()
            _backendMode.value = value
        }

    /**
     * 获取当前后端模式的枚举值
     */
    val terminalBackendMode: BackendMode
        get() = BackendMode.fromValue(_backendMode.value)

    /**
     * 字体类型
     *
     * - "builtin": 使用内置字体（JetBrains Mono Nerd Font）
     * - "system": 使用系统等宽字体
     * - "custom": 使用自定义字体
     */
    override var fontName: String
        get() = _fontName.value
        set(value) {
            prefs.edit().putString(KEY_FONT_NAME, value).apply()
            _fontName.value = value
            Timber.tag(TAG).d("Font type changed to: $value")
            // 清除字体缓存，下次获取时重新加载
            AppFontManager.clearCache()
        }

    /**
     * 光标闪烁启用状态
     */
    override var cursorBlinkEnabled: Boolean
        get() = _cursorBlinkEnabled.value
        set(value) {
            prefs.edit().putBoolean(KEY_CURSOR_BLINK_ENABLED, value).apply()
            _cursorBlinkEnabled.value = value
            Timber.tag(TAG).d("Cursor blink enabled changed to: $value")
        }

    /**
     * 光标闪烁率（毫秒）
     *
     * 有效范围：100-2000ms，超出范围会被限制
     */
    override var cursorBlinkRate: Int
        get() = _cursorBlinkRate.value
        set(value) {
            val clamped = value.coerceIn(CURSOR_BLINK_RATE_MIN, CURSOR_BLINK_RATE_MAX)
            prefs.edit().putInt(KEY_CURSOR_BLINK_RATE, clamped).apply()
            _cursorBlinkRate.value = clamped
            Timber.tag(TAG).d("Cursor blink rate changed to: $clamped ms")
        }

    /**
     * 是否原样显示 linker 兼容告警（关闭内置过滤）。
     *
     * 设置立即对所有会话生效（过滤发生在终端显示层，无需重启）。
     */
    override var showRawLinkerOutput: Boolean
        get() = _showRawLinkerOutput.value
        set(value) {
            prefs.edit().putBoolean(KEY_SHOW_RAW_LINKER_OUTPUT, value).apply()
            _showRawLinkerOutput.value = value
            applyLinkerWarningFilterState(value)
            Timber.tag(TAG).d("Show raw linker output changed to: $value")
        }

    /**
     * 把总闸状态推给终端显示层过滤器。
     * showRawLinkerOutput = true → 关闭过滤（原样显示）；false → 开启过滤。
     */
    private fun applyLinkerWarningFilterState(showRaw: Boolean) {
        TerminalSession.setKnownLinkerWarningFilterEnabled(!showRaw)
    }

    /**
     * 获取当前配置的字体 Typeface
     */
    fun getTypeface(): Typeface = when (_fontName.value) {
        FONT_TYPE_BUILTIN -> AppFontManager.getMonospaceTypeface(context)
        FONT_TYPE_SYSTEM -> Typeface.MONOSPACE
        FONT_TYPE_CUSTOM -> {
            val path = _customFontPath.value
            if (path.isNotEmpty()) {
                AppFontManager.loadCustomFont(path) ?: run {
                    Timber.tag(TAG).w("Failed to load custom font, falling back to built-in")
                    // 自定义字体加载失败，重置为内置字体
                    fontName = FONT_TYPE_BUILTIN
                    AppFontManager.getMonospaceTypeface(context)
                }
            } else {
                Timber.tag(TAG).w("Custom font selected but no path set, resetting to built-in")
                // 没有设置自定义字体路径，重置为内置字体
                fontName = FONT_TYPE_BUILTIN
                AppFontManager.getMonospaceTypeface(context)
            }
        }
        else -> {
            // 未知的字体类型，重置为内置字体
            Timber.tag(TAG).w("Unknown font type: ${_fontName.value}, resetting to built-in")
            fontName = FONT_TYPE_BUILTIN
            AppFontManager.getMonospaceTypeface(context)
        }
    }

    /**
     * 获取当前字体的显示名称
     */
    override fun getFontDisplayName(): String = when (_fontName.value) {
        FONT_TYPE_BUILTIN -> {
            if (AppFontManager.hasBuiltInFont(context)) {
                AppFontManager.getCurrentFontName(context)
            } else {
                Strings.terminal_font_builtin_not_installed.strOr(context)
            }
        }
        FONT_TYPE_SYSTEM -> Strings.terminal_font_system_mono.strOr(context)
        FONT_TYPE_CUSTOM -> {
            val path = _customFontPath.value
            if (path.isNotEmpty()) {
                java.io.File(path).name
            } else {
                Strings.terminal_font_custom_not_set.strOr(context)
            }
        }
        else -> {
            // 未知的字体类型，显示为内置字体
            Strings.terminal_font_builtin.strOr(context)
        }
    }

    /**
     * 设置自定义字体
     *
     * @param fontPath 字体文件路径
     * @return true 如果设置成功，false 如果字体文件无效
     */
    override fun setCustomFont(fontPath: String): Boolean {
        if (!AppFontManager.isValidFontFile(fontPath)) {
            Timber.tag(TAG).w("Invalid font file: $fontPath")
            return false
        }

        prefs.edit().putString(KEY_CUSTOM_FONT_PATH, fontPath).apply()
        _customFontPath.value = fontPath
        Timber.tag(TAG).d("Custom font path changed to: $fontPath")
        // 清除字体缓存
        AppFontManager.clearCache()
        fontName = FONT_TYPE_CUSTOM
        return true
    }

    /**
     * 获取自定义字体路径
     */
    override fun getCustomFontPath(): String = _customFontPath.value

    /**
     * 重置为默认设置
     */
    fun resetToDefaults() {
        fontSize = DEFAULT_FONT_SIZE
        themeName = DEFAULT_THEME
        locale = DEFAULT_LOCALE
        fontName = DEFAULT_FONT_NAME
        _customFontPath.value = ""
        cursorBlinkEnabled = DEFAULT_CURSOR_BLINK_ENABLED
        cursorBlinkRate = DEFAULT_CURSOR_BLINK_RATE
        shellType = DEFAULT_SHELL_TYPE
        backendMode = DEFAULT_BACKEND
        showRawLinkerOutput = DEFAULT_SHOW_RAW_LINKER_OUTPUT
        Timber.tag(TAG).i("Terminal preferences reset to defaults")
    }
}
