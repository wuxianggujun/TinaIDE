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
    fun beyondViewportPageCount_shouldTreatTheLimitAsPagesPerSide() {
        assertThat(EditorPagerSwitchSupport.beyondViewportPageCount(4)).isEqualTo(3)
        assertThat(EditorPagerSwitchSupport.beyondViewportPageCount(5)).isEqualTo(3)
        assertThat(EditorPagerSwitchSupport.beyondViewportPageCount(8)).isEqualTo(3)
    }

    @Test
    fun beyondViewportPageCount_shouldCapLargeTabSets() {
        assertThat(EditorPagerSwitchSupport.beyondViewportPageCount(20))
            .isEqualTo(EditorPagerSwitchSupport.MAX_BEYOND_VIEWPORT_PAGES_PER_SIDE)
    }
}
