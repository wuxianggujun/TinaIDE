package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.ui.focus.FocusRequester
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditorFocusCoordinatorTest {

    @Test
    fun onComposeFocusChanged_shouldDismissCompletionAndHideContextMenuWhenAllFocusLost() = runTest {
        val state = createState("pri")
        state.updateFocus(true)
        state.seedVisibleCompletion(requestId = 1L)
        val contextMenuVisibility = mutableListOf<Boolean>()
        val coordinator = EditorFocusCoordinator(
            state = state,
            interactionController = createController(state, this),
            onContextMenuVisibilityChanged = contextMenuVisibility::add
        )

        coordinator.onComposeFocusChanged(
            composeFocused = false,
            hostFocused = false
        )

        assertThat(state.isFocused).isFalse()
        assertThat(state.showCompletion).isFalse()
        assertThat(state.completionUiState).isEqualTo(CompletionUiState.Hidden)
        assertThat(contextMenuVisibility).containsExactly(false)
    }

    @Test
    fun onComposeFocusChanged_shouldKeepCompletionWhenHostFocusStillActive() = runTest {
        val state = createState("pri")
        state.updateFocus(true)
        state.seedVisibleCompletion(selectedIndex = 1, requestId = 2L)
        val contextMenuVisibility = mutableListOf<Boolean>()
        val coordinator = EditorFocusCoordinator(
            state = state,
            interactionController = createController(state, this),
            onContextMenuVisibilityChanged = contextMenuVisibility::add
        )

        coordinator.onComposeFocusChanged(
            composeFocused = false,
            hostFocused = true
        )

        assertThat(state.isFocused).isTrue()
        assertThat(state.showCompletion).isTrue()
        assertThat(state.completionUiState)
            .isInstanceOf(CompletionUiState.Visible::class.java)
        assertThat(contextMenuVisibility).isEmpty()
    }

    @Test
    fun onComposeFocusChanged_shouldRestoreCursorBlinkWhenEditorRegainsFocus() = runTest {
        val state = createState("value")
        state.cursorBlinkVisible = false
        val contextMenuVisibility = mutableListOf<Boolean>()
        val coordinator = EditorFocusCoordinator(
            state = state,
            interactionController = createController(state, this),
            onContextMenuVisibilityChanged = contextMenuVisibility::add
        )

        coordinator.onComposeFocusChanged(
            composeFocused = true,
            hostFocused = false
        )

        assertThat(state.isFocused).isTrue()
        assertThat(state.cursorBlinkVisible).isTrue()
        assertThat(contextMenuVisibility).isEmpty()
    }

    private fun createState(text: String): EditorState {
        val state = EditorState(RopeTextBuffer(text))
        state.updateMetrics(
            lineHeightPx = 20f,
            charWidthPx = 10f,
            viewportHeightPx = 300f,
            viewportWidthPx = 300f,
            contentStartXPx = 0f
        )
        return state
    }

    private fun createController(
        state: EditorState,
        scope: TestScope
    ): EditorInteractionController = EditorInteractionController(
        state = state,
        coroutineScope = scope,
        focusRequester = FocusRequester(),
        keyboardController = null,
        inputMethodManager = null
    )
}
