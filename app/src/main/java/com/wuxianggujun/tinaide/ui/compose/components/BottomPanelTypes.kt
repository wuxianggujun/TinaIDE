package com.wuxianggujun.tinaide.ui.compose.components

import androidx.annotation.StringRes
import com.wuxianggujun.tinaide.core.i18n.Strings

/**
 * 底部面板 Tab 枚举
 */
enum class BottomPanelTab(@param:StringRes @get:StringRes val titleRes: Int) {
    BUILD_LOG(Strings.bottom_panel_build_log),
    RUN_OUTPUT(Strings.bottom_panel_run_output),
    DIAGNOSTICS(Strings.bottom_panel_diagnostics),
    PERFORMANCE(Strings.bottom_panel_performance),
    OUTLINE(Strings.bottom_panel_outline),
    SYMBOLS(Strings.bottom_panel_symbols),
    BOOKMARKS(Strings.bottom_panel_bookmarks),
    PLUGINS(Strings.bottom_panel_plugins),
    GIT(Strings.bottom_panel_git)
}

data class BottomPanelTabMenuAction(
    @param:StringRes @get:StringRes val titleRes: Int,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    val onClick: () -> Unit
)

/**
 * 默认底栏：问题 / 构建 / 输出（Git 在侧栏，符号/大纲/书签走命令或侧栏导航）。
 * PERFORMANCE / PLUGINS 按需附加；OUTLINE/SYMBOLS/BOOKMARKS/GIT 仍可通过命令打开，但不默认铺满 Tab。
 */
private val defaultNormalModeBottomTabs = listOf(
    BottomPanelTab.DIAGNOSTICS,
    BottomPanelTab.BUILD_LOG,
    BottomPanelTab.RUN_OUTPUT,
)

/** 仍可通过命令/代码打开，但不进默认 Tab 条，避免小屏底栏过载。 */
private val secondaryBottomPanelTabs = setOf(
    BottomPanelTab.OUTLINE,
    BottomPanelTab.SYMBOLS,
    BottomPanelTab.BOOKMARKS,
    BottomPanelTab.GIT,
)

internal fun shouldShowEditorPerformanceTab(
    developerOptionsEnabled: Boolean,
    diagnosticsEnabled: Boolean,
    activeTabSupportsEditorPerformancePanel: Boolean
): Boolean = developerOptionsEnabled && diagnosticsEnabled && activeTabSupportsEditorPerformancePanel

internal fun resolveNormalModeBottomTabs(
    showEditorPerformanceTab: Boolean,
    hasPluginPanels: Boolean = false,
): List<BottomPanelTab> {
    val tabs = defaultNormalModeBottomTabs.toMutableList()
    if (showEditorPerformanceTab) {
        tabs.add(BottomPanelTab.PERFORMANCE)
    }
    if (hasPluginPanels) {
        tabs.add(BottomPanelTab.PLUGINS)
    }
    return tabs
}

/**
 * 若用户通过命令打开了次级 Tab，则临时把它并入可见列表，便于 Tab 行高亮与切换。
 */
internal fun resolveVisibleBottomPanelTabs(
    normalModeTabs: List<BottomPanelTab>,
    selectedBottomTab: BottomPanelTab,
): List<BottomPanelTab> =
    if (selectedBottomTab in secondaryBottomPanelTabs && selectedBottomTab !in normalModeTabs) {
        normalModeTabs + selectedBottomTab
    } else {
        normalModeTabs
    }

internal fun resolveSelectedBottomPanelTab(
    selectedBottomTab: BottomPanelTab,
    normalModeTabs: List<BottomPanelTab>
): BottomPanelTab {
    val visibleTabs = resolveVisibleBottomPanelTabs(normalModeTabs, selectedBottomTab)
    return selectedBottomTab.takeIf { it in visibleTabs } ?: BottomPanelTab.DIAGNOSTICS
}

internal fun formatBottomPanelTabBadgeCount(count: Int): String? = when {
    count <= 0 -> null
    count > 999 -> "999+"
    else -> count.toString()
}
