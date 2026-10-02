package com.wuxianggujun.tinaide.core.terminal

import kotlinx.coroutines.flow.StateFlow

/**
 * 终端配置接口
 *
 * 抽象终端配置访问，避免 feature:settings 直接依赖 feature:terminal。
 */
interface ITerminalPreferences {

    companion object {
        // 字体大小常量（由实现类提供具体值）
        const val MIN_FONT_SIZE = 8f
        const val MAX_FONT_SIZE = 32f

        // 光标闪烁常量
        const val CURSOR_BLINK_RATE_MIN = 100 // 最小闪烁率 100ms
        const val CURSOR_BLINK_RATE_MAX = 2000 // 最大闪烁率 2000ms
        const val DEFAULT_CURSOR_BLINK_RATE = 500 // 默认闪烁率 500ms
    }

    // StateFlow 属性
    val fontSizeFlow: StateFlow<Float>
    val themeNameFlow: StateFlow<String>
    val localeFlow: StateFlow<String>
    val shellTypeFlow: StateFlow<String>
    val backendModeFlow: StateFlow<String>
    val fontNameFlow: StateFlow<String>
    val cursorBlinkEnabledFlow: StateFlow<Boolean>
    val cursorBlinkRateFlow: StateFlow<Int>
    val showRawLinkerOutputFlow: StateFlow<Boolean>

    // 读写属性
    var fontSize: Float
    var themeName: String
    var locale: String
    var shellType: String
    var backendMode: String
    var fontName: String
    var cursorBlinkEnabled: Boolean
    var cursorBlinkRate: Int

    /**
     * 是否原样显示 linker 兼容告警（关闭内置的已知噪音过滤）。
     *
     * 默认 false（保持过滤）。作为过滤器的安全阀：若过滤逻辑将来误吞真实告警，
     * 用户可打开它，让所有会话（交互终端 + Run）原样输出 linker 信息。
     */
    var showRawLinkerOutput: Boolean

    /**
     * 获取字体显示名称
     */
    fun getFontDisplayName(): String

    /**
     * 设置自定义字体
     * @return 是否设置成功
     */
    fun setCustomFont(fontPath: String): Boolean

    /**
     * 获取自定义字体路径
     */
    fun getCustomFontPath(): String
}
