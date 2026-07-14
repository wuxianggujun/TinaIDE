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

private val defaultNormalModeBottomTabs = listOf(
    BottomPanelTab.BUILD_LOG,
    BottomPanelTab.DIAGNOSTICS,
    BottomPanelTab.PERFORMANCE,
    BottomPanelTab.OUTLINE,
    BottomPanelTab.SYMBOLS,
    BottomPanelTab.BOOKMARKS,
    BottomPanelTab.PLUGINS,
    BottomPanelTab.GIT
)

internal fun shouldShowEditorPerformanceTab(
    developerOptionsEnabled: Boolean,
    diagnosticsEnabled: Boolean,
    activeTabSupportsEditorPerformancePanel: Boolean
): Boolean = developerOptionsEnabled && diagnosticsEnabled && activeTabSupportsEditorPerformancePanel

internal fun resolveNormalModeBottomTabs(
    showEditorPerformanceTab: Boolean,
    hasPluginPanels: Boolean = false,
): List<BottomPanelTab> = defaultNormalModeBottomTabs.filter { tab ->
    (tab != BottomPanelTab.PERFORMANCE || showEditorPerformanceTab) &&
        (tab != BottomPanelTab.PLUGINS || hasPluginPanels)
}

internal fun resolveSelectedBottomPanelTab(
    selectedBottomTab: BottomPanelTab,
    normalModeTabs: List<BottomPanelTab>
): BottomPanelTab = selectedBottomTab.takeIf { it in normalModeTabs } ?: BottomPanelTab.BUILD_LOG

internal fun formatBottomPanelTabBadgeCount(count: Int): String? = when {
    count <= 0 -> null
    count > 999 -> "999+"
    else -> count.toString()
}
