package com.wuxianggujun.tinaide.ui.compose.components

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import org.junit.Test

class SwipeableDrawerStateTest {

    @Test
    fun `updateDrawerWidth keeps an open drawer open`() {
        val state = SwipeableDrawerState(
            initialOpen = true,
            initialDrawerWidthPx = 300f,
            coroutineScope = TestScope(),
        )
        state.selectedTab = DrawerTab.SYMBOLS

        state.updateDrawerWidth(500f)

        assertThat(state.drawerWidthPx).isEqualTo(500f)
        assertThat(state.offsetX).isEqualTo(500f)
        assertThat(state.isOpen).isTrue()
        assertThat(state.selectedTab).isEqualTo(DrawerTab.SYMBOLS)
    }

    @Test
    fun `updateDrawerWidth preserves a partial drag progress`() {
        val state = SwipeableDrawerState(
            initialOpen = true,
            initialDrawerWidthPx = 300f,
            coroutineScope = TestScope(),
        )
        state.startDrag()
        state.drag(-225f)

        state.updateDrawerWidth(600f)

        assertThat(state.progress).isWithin(0.0001f).of(0.25f)
        assertThat(state.offsetX).isWithin(0.0001f).of(150f)
    }

    @Test
    fun `startDrag cancels a queued open animation`() {
        val scope = TestScope()
        val state = SwipeableDrawerState(
            initialOpen = false,
            initialDrawerWidthPx = 300f,
            coroutineScope = scope,
        )

        state.open()
        state.startDrag()
        state.drag(75f)
        scope.testScheduler.runCurrent()

        assertThat(state.isDragging).isTrue()
        assertThat(state.offsetX).isEqualTo(75f)
    }
}
