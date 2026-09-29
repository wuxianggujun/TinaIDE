package com.wuxianggujun.tinaide.core.editorview

import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntOffset
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
class EditorOverlaysIntegrationTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun editorCompletionOverlay_shouldApplyCompletionAfterHorizontalScrollReposition() {
        val lineText = "veryLongPrefixSegment_pri"
        val buffer = RopeTextBuffer().apply { insert(0, lineText) }
        val state = createState(buffer)
        var session: TinaEditorSession? = null
        val items = listOf(
            EditorCompletionItem(label = "print"),
            EditorCompletionItem(
                label = "private",
                insertText = "private",
                textEdit = EditorCompletionTextEdit(
                    startLine = 0,
                    startColumn = lineText.length - 3,
                    endLine = 0,
                    endColumn = lineText.length,
                    newText = "private"
                )
            )
        )

        composeRule.setContent {
            val resolvedSession = rememberTinaEditorSession(state)
            session = resolvedSession
            mountEditorOverlays(resolvedSession)
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            val resolvedSession = checkNotNull(session)
            configureViewport(
                session = resolvedSession,
                state = state,
                canvasWidthPx = 360f,
                canvasHeightPx = 220f,
                contentStartXPx = 36f,
                canvasOriginInWindowPx = IntOffset(20, 60)
            )
            state.moveCursorTo(buffer.length)
            state.scrollOffsetXPx = 84f
            state.seedVisibleCompletion(
                items = items,
                selectedIndex = 0,
                requestId = 1L
            )
        }
        composeRule.waitForIdle()

        assertThat(composeRule.onNodeWithTag(COMPLETION_POPUP_TAG).fetchSemanticsNode()).isNotNull()

        composeRule.runOnIdle {
            state.scrollOffsetXPx = 148f
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(completionPopupRowTag(1)).performClick()
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertThat(state.textBuffer.substring(0, state.textBuffer.length))
                .isEqualTo("veryLongPrefixSegment_private")
            assertThat(state.completionUiState).isEqualTo(CompletionUiState.Hidden)
        }
    }

    @Test
    fun editorCompletionOverlay_shouldShrinkAndRemainSelectableAfterImeInsetChange() {
        val lineText = "veryLongPrefixSegment_pri"
        val buffer = RopeTextBuffer().apply {
            insert(0, "line-0()\n$lineText\nline-2()")
        }
        val state = createState(buffer)
        var session: TinaEditorSession? = null
        val viewportMetrics = mutableStateOf(
            EditorPopupViewportMetrics(
                windowWidthPx = 500f,
                windowHeightPx = 260f,
                imeBottomInsetPx = 0f
            )
        )
        val completionLayout = mutableStateOf<CompletionPopupLayout?>(null)
        val items = buildList {
            add(EditorCompletionItem(label = "print"))
            add(
                EditorCompletionItem(
                    label = "private",
                    insertText = "private",
                    textEdit = EditorCompletionTextEdit(
                        startLine = 1,
                        startColumn = lineText.length - 3,
                        endLine = 1,
                        endColumn = lineText.length,
                        newText = "private"
                    )
                )
            )
            repeat(6) { index ->
                add(EditorCompletionItem(label = "probe_$index"))
            }
        }

        composeRule.setContent {
            CompositionLocalProvider(
                LocalEditorPopupViewportMetricsOverride provides viewportMetrics.value,
                LocalEditorPopupLayoutProbe provides EditorPopupLayoutProbe(
                    onCompletionLayout = { completionLayout.value = it }
                )
            ) {
                val resolvedSession = rememberTinaEditorSession(state)
                session = resolvedSession
                mountEditorOverlays(resolvedSession)
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            val resolvedSession = checkNotNull(session)
            configureViewport(
                session = resolvedSession,
                state = state,
                canvasWidthPx = 420f,
                canvasHeightPx = 240f,
                contentStartXPx = 32f,
                canvasOriginInWindowPx = IntOffset(20, 60)
            )
            state.moveCursorTo(buffer.positionToOffset(1, lineText.length))
            state.seedVisibleCompletion(
                items = items,
                selectedIndex = 0,
                requestId = 2L
            )
        }
        composeRule.waitForIdle()

        val beforeImeLayout = checkNotNull(completionLayout.value)

        composeRule.runOnIdle {
            viewportMetrics.value = viewportMetrics.value.copy(imeBottomInsetPx = 80f)
        }
        composeRule.waitForIdle()

        val afterImeLayout = checkNotNull(completionLayout.value)

        assertThat(afterImeLayout.maxHeightPx).isLessThan(beforeImeLayout.maxHeightPx)
        assertThat(composeRule.onNodeWithTag(COMPLETION_POPUP_TAG).fetchSemanticsNode()).isNotNull()

        composeRule.onNodeWithTag(completionPopupRowTag(1)).performClick()
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertThat(state.textBuffer.getLine(1)).isEqualTo("veryLongPrefixSegment_private")
            assertThat(state.completionUiState).isEqualTo(CompletionUiState.Hidden)
        }
    }

    @Test
    fun editorSignatureHelpOverlay_shouldRemainVisibleAndTrackSelectionAfterVerticalScrollReposition() {
        val buffer = RopeTextBuffer().apply {
            insert(0, (0..12).joinToString("\n") { "line-$it(value)" })
        }
        val state = createState(buffer)
        var session: TinaEditorSession? = null
        val result = SignatureHelpResult(
            signatures = listOf(
                "sum(Int count, String label)",
                "sum(Int count, Boolean enabled)"
            ),
            activeSignature = 0,
            activeParameter = 1
        )

        composeRule.setContent {
            val resolvedSession = rememberTinaEditorSession(state)
            session = resolvedSession
            mountEditorOverlays(resolvedSession)
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            val resolvedSession = checkNotNull(session)
            configureViewport(
                session = resolvedSession,
                state = state,
                canvasWidthPx = 420f,
                canvasHeightPx = 240f,
                contentStartXPx = 32f,
                canvasOriginInWindowPx = IntOffset(20, 60)
            )
            state.moveCursorTo(buffer.positionToOffset(9, 8))
            state.scrollOffsetPx = 40f
            state.seedVisibleSignatureHelp(
                result = result,
                requestId = 1L
            )
        }
        composeRule.waitForIdle()

        assertThat(composeRule.onNodeWithTag(SIGNATURE_HELP_POPUP_TAG).fetchSemanticsNode()).isNotNull()

        composeRule.runOnIdle {
            state.scrollOffsetPx = 92f
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertThat(state.selectSignatureHelp(1)).isTrue()
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertThat(state.signatureHelpSelectedSignatureIndex).isEqualTo(1)
        }
        assertThat(composeRule.onNodeWithText("2/2").fetchSemanticsNode()).isNotNull()
    }

    @Test
    fun editorSelectionContextMenuOverlay_shouldSelectAllAfterAnchorMovesNearRightEdge() {
        val buffer = RopeTextBuffer().apply { insert(0, "alpha beta gamma") }
        val state = createState(buffer)
        var session: TinaEditorSession? = null

        composeRule.setContent {
            val resolvedSession = rememberTinaEditorSession(state)
            session = resolvedSession
            mountEditorOverlays(resolvedSession)
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            val resolvedSession = checkNotNull(session)
            configureViewport(
                session = resolvedSession,
                state = state,
                canvasWidthPx = 320f,
                canvasHeightPx = 180f,
                contentStartXPx = 28f,
                canvasOriginInWindowPx = IntOffset(20, 60)
            )
            state.moveCursorTo(buffer.positionToOffset(0, 5))
            state.selectionRange = OffsetRange(anchor = 0, caret = 5)
            resolvedSession.ui.contextMenuVisible = true
            resolvedSession.ui.contextMenuOffset = IntOffset(296, 152)
        }
        composeRule.waitForIdle()

        assertThat(composeRule.onNodeWithTag(SELECTION_CONTEXT_MENU_TAG).fetchSemanticsNode()).isNotNull()

        composeRule.runOnIdle {
            checkNotNull(session).ui.contextMenuOffset = IntOffset(248, 118)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SELECTION_CONTEXT_MENU_MORE_ACTION_TAG).performClick()
        composeRule.onNodeWithTag(SELECTION_CONTEXT_MENU_SELECT_ALL_ACTION_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            val selection = state.selectionRange
            assertThat(selection).isNotNull()
            assertThat(selection?.start).isEqualTo(0)
            assertThat(selection?.end).isEqualTo(state.textBuffer.length)
            assertThat(checkNotNull(session).ui.contextMenuVisible).isTrue()
        }
    }

    @Composable
    private fun mountEditorOverlays(session: TinaEditorSession) {
        EditorSelectionContextMenuOverlay(session)
        EditorSignatureHelpOverlay(session)
        EditorCompletionOverlay(session)
    }

    private fun createState(buffer: RopeTextBuffer): EditorState = EditorState(
        textBuffer = buffer,
        config = EditorConfig(
            wordWrap = false,
            codeFolding = false,
            tabSize = 4
        )
    ).apply {
        typeface = Typeface.MONOSPACE
        fontSizeSp = 14f
    }

    private fun configureViewport(
        session: TinaEditorSession,
        state: EditorState,
        canvasWidthPx: Float,
        canvasHeightPx: Float,
        contentStartXPx: Float,
        canvasOriginInWindowPx: IntOffset
    ) {
        state.updateMetrics(
            lineHeightPx = 20f,
            charWidthPx = 10f,
            viewportHeightPx = canvasHeightPx,
            viewportWidthPx = canvasWidthPx,
            contentStartXPx = contentStartXPx
        )
        session.ui.canvasWidthPx = canvasWidthPx
        session.ui.canvasHeightPx = canvasHeightPx
        session.ui.contentStartXPx = contentStartXPx
        session.ui.canvasOriginInWindowPx = canvasOriginInWindowPx
    }
}
