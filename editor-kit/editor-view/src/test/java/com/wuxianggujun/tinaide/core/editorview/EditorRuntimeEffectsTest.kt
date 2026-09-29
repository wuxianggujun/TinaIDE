package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.editorlsp.SignatureHelpResult
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorRuntimeEffectsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun editorRuntimeEffects_shouldDismissHoverAndSignatureOnFocusLossButKeepCompletion() {
        val state = EditorState(RopeTextBuffer("pri")).apply {
            updateMetrics(
                lineHeightPx = 20f,
                charWidthPx = 10f,
                viewportHeightPx = 300f,
                viewportWidthPx = 320f,
                contentStartXPx = 0f
            )
            updateFocus(true)
            cursorBlinkVisible = true
            seedVisibleHover()
            seedVisibleSignatureHelp(
                result = SignatureHelpResult(
                    signatures = listOf("print(String value)"),
                    activeSignature = 0,
                    activeParameter = 0
                ),
                requestId = 1L
            )
            seedVisibleCompletion(requestId = 2L)
        }

        composeRule.setContent {
            val density = LocalDensity.current
            val view = LocalView.current
            val scope = rememberCoroutineScope()
            val gestureHandler = remember { EditorGestureHandler() }
            val touchDiagnostics = remember { EditorTouchDiagnostics() }
            val scrollbarVisibilityCoordinator = remember {
                ScrollbarVisibilityCoordinator(
                    state = state,
                    coroutineScope = scope,
                    isScrollbarDragActive = { false }
                )
            }
            val gestureExclusionCoordinator = remember(view) {
                EditorGestureExclusionCoordinator(view, touchDiagnostics)
            }
            val focusRequester = remember { FocusRequester() }
            val interactionController = remember {
                EditorInteractionController(
                    state = state,
                    coroutineScope = scope,
                    focusRequester = focusRequester,
                    keyboardController = null,
                    inputMethodManager = null
                )
            }
            val selectionMagnifier = remember(view) {
                SelectionMagnifierController(view)
            }
            val ui = remember(density) { TinaEditorUiState(density) }

            EditorRuntimeEffects(
                state = state,
                ui = ui,
                density = density,
                isTransformInProgress = false,
                isHandleDragging = false,
                gestureHandler = gestureHandler,
                scrollbarVisibilityCoordinator = scrollbarVisibilityCoordinator,
                gestureExclusionCoordinator = gestureExclusionCoordinator,
                focusRequester = focusRequester,
                interactionController = interactionController,
                selectionMagnifier = selectionMagnifier
            )
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            state.updateFocus(false)
        }
        composeRule.waitForIdle()

        assertThat(state.isFocused).isFalse()
        assertThat(state.cursorBlinkVisible).isFalse()
        assertThat(state.hoverUiState).isEqualTo(HoverUiState.Hidden)
        assertThat(state.signatureHelpUiState).isEqualTo(SignatureHelpUiState.Hidden)
        assertThat(state.showCompletion).isTrue()
        assertThat(state.completionUiState).isInstanceOf(CompletionUiState.Visible::class.java)
    }

}
