package com.wuxianggujun.tinaide.ui.compose.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BottomPanelTabResolutionTest {

    @Test
    fun `shouldShowEditorPerformanceTab requires all gates enabled`() {
        assertThat(
            shouldShowEditorPerformanceTab(
                developerOptionsEnabled = true,
                diagnosticsEnabled = true,
                activeTabSupportsEditorPerformancePanel = true
            )
        ).isTrue()

        assertThat(
            shouldShowEditorPerformanceTab(
                developerOptionsEnabled = false,
                diagnosticsEnabled = true,
                activeTabSupportsEditorPerformancePanel = true
            )
        ).isFalse()
        assertThat(
            shouldShowEditorPerformanceTab(
                developerOptionsEnabled = true,
                diagnosticsEnabled = false,
                activeTabSupportsEditorPerformancePanel = true
            )
        ).isFalse()
        assertThat(
            shouldShowEditorPerformanceTab(
                developerOptionsEnabled = true,
                diagnosticsEnabled = true,
                activeTabSupportsEditorPerformancePanel = false
            )
        ).isFalse()
    }

    @Test
    fun `resolveNormalModeBottomTabs keeps primary tabs and optional performance`() {
        val visibleTabs = resolveNormalModeBottomTabs(showEditorPerformanceTab = true)
        val hiddenTabs = resolveNormalModeBottomTabs(showEditorPerformanceTab = false)

        assertThat(visibleTabs).contains(BottomPanelTab.PERFORMANCE)
        assertThat(hiddenTabs).doesNotContain(BottomPanelTab.PERFORMANCE)
        // 默认：问题 / 构建 / 输出；Git/符号不进默认条
        assertThat(hiddenTabs).containsExactly(
            BottomPanelTab.DIAGNOSTICS,
            BottomPanelTab.BUILD_LOG,
            BottomPanelTab.RUN_OUTPUT,
        ).inOrder()
        assertThat(hiddenTabs).doesNotContain(BottomPanelTab.GIT)
        assertThat(hiddenTabs).doesNotContain(BottomPanelTab.SYMBOLS)
    }

    @Test
    fun `resolveSelectedBottomPanelTab falls back to diagnostics when selected tab becomes unavailable`() {
        val hiddenTabs = resolveNormalModeBottomTabs(showEditorPerformanceTab = false)

        assertThat(
            resolveSelectedBottomPanelTab(
                selectedBottomTab = BottomPanelTab.PERFORMANCE,
                normalModeTabs = hiddenTabs
            )
        ).isEqualTo(BottomPanelTab.DIAGNOSTICS)
    }

    @Test
    fun `resolveSelectedBottomPanelTab keeps secondary tab when opened by command`() {
        val visibleTabs = resolveNormalModeBottomTabs(showEditorPerformanceTab = false)

        assertThat(
            resolveSelectedBottomPanelTab(
                selectedBottomTab = BottomPanelTab.BOOKMARKS,
                normalModeTabs = visibleTabs
            )
        ).isEqualTo(BottomPanelTab.BOOKMARKS)
        assertThat(
            resolveVisibleBottomPanelTabs(
                normalModeTabs = visibleTabs,
                selectedBottomTab = BottomPanelTab.BOOKMARKS,
            )
        ).contains(BottomPanelTab.BOOKMARKS)
    }

    @Test
    fun `resolveNormalModeBottomTabs shows plugins only when enabled panels exist`() {
        val hidden = resolveNormalModeBottomTabs(
            showEditorPerformanceTab = false,
            hasPluginPanels = false,
        )
        val visible = resolveNormalModeBottomTabs(
            showEditorPerformanceTab = false,
            hasPluginPanels = true,
        )

        assertThat(hidden).doesNotContain(BottomPanelTab.PLUGINS)
        assertThat(visible).contains(BottomPanelTab.PLUGINS)
    }

    @Test
    fun `formatBottomPanelTabBadgeCount hides empty count and caps large values`() {
        assertThat(formatBottomPanelTabBadgeCount(0)).isNull()
        assertThat(formatBottomPanelTabBadgeCount(-1)).isNull()
        assertThat(formatBottomPanelTabBadgeCount(7)).isEqualTo("7")
        assertThat(formatBottomPanelTabBadgeCount(999)).isEqualTo("999")
        assertThat(formatBottomPanelTabBadgeCount(1000)).isEqualTo("999+")
    }
}
