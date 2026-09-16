package com.wuxianggujun.tinaide.ui.compose.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EditorPagerSwitchSupportTest {

    @Test
    fun beyondViewportPageCount_shouldKeepSinglePageFromComposingNeighbors() {
        assertThat(EditorPagerSwitchSupport.beyondViewportPageCount(0)).isEqualTo(0)
        assertThat(EditorPagerSwitchSupport.beyondViewportPageCount(1)).isEqualTo(0)
    }

    @Test
    fun beyondViewportPageCount_shouldKeepBothPagesWhenTwoTabsAreOpen() {
        assertThat(EditorPagerSwitchSupport.beyondViewportPageCount(2)).isEqualTo(1)
    }

    @Test
    fun beyondViewportPageCount_shouldKeepAllPagesBelowTheComposeCap() {
        assertThat(EditorPagerSwitchSupport.beyondViewportPageCount(5)).isEqualTo(4)
        assertThat(EditorPagerSwitchSupport.beyondViewportPageCount(8)).isEqualTo(7)
    }

    @Test
    fun beyondViewportPageCount_shouldCapLargeTabSets() {
        assertThat(EditorPagerSwitchSupport.beyondViewportPageCount(20))
            .isEqualTo(EditorPagerSwitchSupport.MAX_COMPOSED_PAGES - 1)
    }
}
