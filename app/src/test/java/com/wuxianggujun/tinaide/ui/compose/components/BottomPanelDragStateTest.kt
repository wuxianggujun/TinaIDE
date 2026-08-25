package com.wuxianggujun.tinaide.ui.compose.components

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BottomPanelDragStateTest {

    @Test
    fun `collapse closes an expanded panel for back navigation`() = runTest {
        val state = BottomPanelDragState(
            initialExpanded = false,
            minHeight = 0f,
            maxHeight = 1_000f,
        )

        state.snapToFraction(PanelHeightPreset.DEFAULT)
        assertThat(state.isExpanded).isTrue()

        state.collapse()

        assertThat(state.isExpanded).isFalse()
    }
}
