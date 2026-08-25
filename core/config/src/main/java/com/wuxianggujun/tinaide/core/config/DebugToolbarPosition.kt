package com.wuxianggujun.tinaide.core.config

import android.content.Context
import androidx.annotation.StringRes
import com.wuxianggujun.tinaide.core.i18n.Strings

/**
 * 调试工具栏位置配置。
 *
 * 默认顶栏：编辑器优先，避免与底栏双控件迷路。
 */
enum class DebugToolbarPosition(
    val value: String,
    @param:StringRes @get:StringRes val displayNameRes: Int,
) {
    /**
     * 顶部显示（默认）
     * - 始终可见，不依赖底栏展开
     */
    TOP("top", Strings.debug_toolbar_top),

    /**
     * 底部显示
     * - 不占用顶栏；需展开底栏才看到
     */
    BOTTOM("bottom", Strings.debug_toolbar_bottom),

    /**
     * 两处都显示
     * - 最大灵活度，占用更多空间
     */
    BOTH("both", Strings.debug_toolbar_both);

    fun getDisplayName(context: Context): String = context.getString(displayNameRes)

    companion object {
        fun fromString(value: String): DebugToolbarPosition =
            entries.find { it.value == value } ?: TOP

        /** 默认顶栏调试条 */
        val DEFAULT = TOP
    }
}
