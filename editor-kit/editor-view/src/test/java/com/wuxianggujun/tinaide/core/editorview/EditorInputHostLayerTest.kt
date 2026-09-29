package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorInputHostLayerTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun editorInputHostLayer_shouldDismissCompletionWhenWindowFocusLostAndComposeInactive() {
        val state = createState("pri").apply {
            updateFocus(true)
            seedVisibleCompletion(requestId = 1L)
        }
        val contextMenuVisibility = mutableListOf<Boolean>()
        var interactionController: EditorInteractionController? = null
        var composeFocusActive by mutableStateOf(false)

        composeRule.setContent {
            val scope = rememberCoroutineScope()
            val focusRequester = remember { FocusRequester() }
            val controller = remember {
                EditorInteractionController(
                    state = state,
                    coroutineScope = scope,
                    focusRequester = focusRequester,
                    keyboardController = null,
                    inputMethodManager = null
                )
            }
            val coordinator = remember {
                EditorFocusCoordinator(
                    state = state,
                    interactionController = controller,
                    onContextMenuVisibilityChanged = contextMenuVisibility::add
                )
            }
            interactionController = controller

            EditorInputHostLayer(
                interactionController = controller,
                focusCoordinator = coordinator,
                isComposeFocusActive = { composeFocusActive }
            )
        }
        composeRule.waitForIdle()

        val host = checkNotNull(interactionController?.inputHostView)
        composeRule.runOnIdle {
            host.requestFocus()
            host.dispatchWindowFocusChanged(false)
        }
        composeRule.waitForIdle()

        assertThat(state.isFocused).isFalse()
        assertThat(state.showCompletion).isFalse()
        assertThat(state.completionUiState).isEqualTo(CompletionUiState.Hidden)
        assertThat(contextMenuVisibility).containsExactly(false)
    }

    @Test
    fun editorInputHostLayer_shouldDismissCompletionWhenHostDetachedAndComposeInactive() {
        val state = createState("pri").apply {
            updateFocus(true)
            seedVisibleCompletion(requestId = 2L)
        }
        val contextMenuVisibility = mutableListOf<Boolean>()
        var composeFocusActive by mutableStateOf(false)
        var showHost by mutableStateOf(true)

        composeRule.setContent {
            val scope = rememberCoroutineScope()
            val focusRequester = remember { FocusRequester() }
            val controller = remember {
                EditorInteractionController(
                    state = state,
                    coroutineScope = scope,
                    focusRequester = focusRequester,
                    keyboardController = null,
                    inputMethodManager = null
                )
            }
            val coordinator = remember {
                EditorFocusCoordinator(
                    state = state,
                    interactionController = controller,
                    onContextMenuVisibilityChanged = contextMenuVisibility::add
                )
            }

            if (showHost) {
                EditorInputHostLayer(
                    interactionController = controller,
                    focusCoordinator = coordinator,
                    isComposeFocusActive = { composeFocusActive }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            showHost = false
        }
        composeRule.waitForIdle()

        assertThat(state.isFocused).isFalse()
        assertThat(state.showCompletion).isFalse()
        assertThat(state.completionUiState).isEqualTo(CompletionUiState.Hidden)
        assertThat(contextMenuVisibility).containsExactly(false)
    }

    @Test
    fun editorInputHostLayer_shouldKeepCompletionWhenHostDetachedButComposeStillFocused() {
        val state = createState("pri").apply {
            updateFocus(true)
            seedVisibleCompletion(requestId = 3L)
        }
        val contextMenuVisibility = mutableListOf<Boolean>()
        var composeFocusActive by mutableStateOf(true)
        var showHost by mutableStateOf(true)

        composeRule.setContent {
            val scope = rememberCoroutineScope()
            val focusRequester = remember { FocusRequester() }
            val controller = remember {
                EditorInteractionController(
                    state = state,
                    coroutineScope = scope,
                    focusRequester = focusRequester,
                    keyboardController = null,
                    inputMethodManager = null
                )
            }
            val coordinator = remember {
                EditorFocusCoordinator(
                    state = state,
                    interactionController = controller,
                    onContextMenuVisibilityChanged = contextMenuVisibility::add
                )
            }

            if (showHost) {
                EditorInputHostLayer(
                    interactionController = controller,
                    focusCoordinator = coordinator,
                    isComposeFocusActive = { composeFocusActive }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            showHost = false
        }
        composeRule.waitForIdle()

        assertThat(state.isFocused).isTrue()
        assertThat(state.showCompletion).isTrue()
        assertThat(state.completionUiState)
            .isInstanceOf(CompletionUiState.Visible::class.java)
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
}
