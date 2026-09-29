package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.ui.focus.FocusRequester
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.editorlsp.SignatureHelpResult
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditorInteractionControllerTest {

    @Test
    fun moveCursorTo_shouldRefreshSignatureHelpAfterDebounceWhenVisibleAndContextActive() = runTest {
        val state = createState("print(value)")
        state.cursorOffset = 7
        state.seedVisibleSignatureHelp(result = sampleSignatureHelpResult())
        var requestCount = 0
        state.onRequestSignatureHelp = {
            requestCount++
            sampleSignatureHelpResult()
        }
        val controller = createController(state, this)
        val job = launchEventBridge(
            scope = this,
            state = state,
            controller = controller,
            allowAutoSignatureHelpRefresh = true
        )

        state.moveCursorTo(8)

        advanceTimeBy(39)
        assertThat(requestCount).isEqualTo(0)

        advanceTimeBy(1)
        advanceUntilIdle()

        assertThat(requestCount).isEqualTo(1)
        assertThat(state.signatureHelpUiState).isInstanceOf(SignatureHelpUiState.Visible::class.java)
        job.cancel()
    }

    @Test
    fun moveCursorTo_shouldNotRefreshSignatureHelpWhenAutoRefreshIsDisabled() = runTest {
        val state = createState("print(value)")
        state.cursorOffset = 7
        state.seedVisibleSignatureHelp(result = sampleSignatureHelpResult())
        var requestCount = 0
        state.onRequestSignatureHelp = {
            requestCount++
            sampleSignatureHelpResult()
        }
        val controller = createController(state, this)
        val job = launchEventBridge(
            scope = this,
            state = state,
            controller = controller,
            allowAutoSignatureHelpRefresh = false
        )

        state.moveCursorTo(8)
        advanceTimeBy(40)
        advanceUntilIdle()

        assertThat(requestCount).isEqualTo(0)
        assertThat(state.signatureHelpUiState).isInstanceOf(SignatureHelpUiState.Visible::class.java)
        job.cancel()
    }

    @Test
    fun selectRange_shouldDismissVisibleSignatureHelp() = runTest {
        val state = createState("print(value)")
        state.cursorOffset = 7
        state.seedVisibleSignatureHelp(result = sampleSignatureHelpResult())
        val controller = createController(state, this)
        val job = launchEventBridge(
            scope = this,
            state = state,
            controller = controller,
            allowAutoSignatureHelpRefresh = true
        )

        state.selectRange(startOffset = 6, endOffset = 8)
        advanceUntilIdle()

        assertThat(state.signatureHelpUiState).isEqualTo(SignatureHelpUiState.Hidden)
        job.cancel()
    }

    @Test
    fun updateFocus_shouldDismissVisibleSignatureHelpWhenEditorLosesFocus() = runTest {
        val state = createState("print(value)")
        state.isFocused = true
        state.seedVisibleSignatureHelp(result = sampleSignatureHelpResult())
        val controller = createController(state, this)
        val job = launchEventBridge(
            scope = this,
            state = state,
            controller = controller,
            allowAutoSignatureHelpRefresh = true
        )

        state.updateFocus(false)
        advanceUntilIdle()

        assertThat(state.signatureHelpUiState).isEqualTo(SignatureHelpUiState.Hidden)
        job.cancel()
    }

    @Test
    fun replaceRange_shouldSuppressImmediateAutoRefreshUntilNextCursorMove() = runTest {
        val state = createState("print(value)")
        state.cursorOffset = 7
        state.seedVisibleSignatureHelp(result = sampleSignatureHelpResult())
        var requestCount = 0
        state.onRequestSignatureHelp = {
            requestCount++
            sampleSignatureHelpResult()
        }
        val controller = createController(state, this)
        val job = launchEventBridge(
            scope = this,
            state = state,
            controller = controller,
            allowAutoSignatureHelpRefresh = true
        )

        state.replaceRange(startOffset = 10, endOffset = 10, replacement = "X")
        advanceTimeBy(40)
        advanceUntilIdle()

        assertThat(requestCount).isEqualTo(0)

        state.moveCursorTo(state.cursorOffset - 1)
        advanceTimeBy(40)
        advanceUntilIdle()

        assertThat(requestCount).isEqualTo(1)
        job.cancel()
    }

    @Test
    fun moveCursorTo_shouldRefreshSignatureHelpAcrossLineCommentInsideCall() = runTest {
        val (state, initialCursor, targetCursor) = createMarkedState(
            "print(value, // )\n|nextArg|)"
        )
        state.cursorOffset = initialCursor
        state.seedVisibleSignatureHelp(result = sampleSignatureHelpResult())
        var requestCount = 0
        state.onRequestSignatureHelp = {
            requestCount++
            sampleSignatureHelpResult()
        }
        val controller = createController(state, this)
        val job = launchEventBridge(
            scope = this,
            state = state,
            controller = controller,
            allowAutoSignatureHelpRefresh = true
        )

        state.moveCursorTo(targetCursor)
        advanceTimeBy(40)
        advanceUntilIdle()

        assertThat(requestCount).isEqualTo(1)
        assertThat(state.signatureHelpUiState).isInstanceOf(SignatureHelpUiState.Visible::class.java)
        job.cancel()
    }

    @Test
    fun moveCursorTo_shouldDismissSignatureHelpWhenCommentOnlyContainsOpenParenthesis() = runTest {
        val (state, initialCursor, targetCursor) = createMarkedState(
            "print(value) // (|\n|"
        )
        state.cursorOffset = initialCursor
        state.seedVisibleSignatureHelp(result = sampleSignatureHelpResult())
        val controller = createController(state, this)
        val job = launchEventBridge(
            scope = this,
            state = state,
            controller = controller,
            allowAutoSignatureHelpRefresh = true
        )

        state.moveCursorTo(targetCursor)
        advanceUntilIdle()

        assertThat(state.signatureHelpUiState).isEqualTo(SignatureHelpUiState.Hidden)
        job.cancel()
    }

    @Test
    fun moveCursorTo_shouldRefreshSignatureHelpInsideTrailingLambda() = runTest {
        val (state, initialCursor, targetCursor) = createMarkedState(
            "fold(0) { acc, value -> |value + 1| }"
        )
        state.cursorOffset = initialCursor
        state.seedVisibleSignatureHelp(result = sampleSignatureHelpResult())
        var requestCount = 0
        state.onRequestSignatureHelp = {
            requestCount++
            sampleSignatureHelpResult()
        }
        val controller = createController(state, this)
        val job = launchEventBridge(
            scope = this,
            state = state,
            controller = controller,
            allowAutoSignatureHelpRefresh = true
        )

        state.moveCursorTo(targetCursor)
        advanceTimeBy(40)
        advanceUntilIdle()

        assertThat(requestCount).isEqualTo(1)
        assertThat(state.signatureHelpUiState).isInstanceOf(SignatureHelpUiState.Visible::class.java)
        job.cancel()
    }

    @Test
    fun moveCursorTo_shouldDismissSignatureHelpAfterTrailingLambdaCloses() = runTest {
        val (state, initialCursor, targetCursor) = createMarkedState(
            "fold(0) { acc, value -> value| }|"
        )
        state.cursorOffset = initialCursor
        state.seedVisibleSignatureHelp(result = sampleSignatureHelpResult())
        val controller = createController(state, this)
        val job = launchEventBridge(
            scope = this,
            state = state,
            controller = controller,
            allowAutoSignatureHelpRefresh = true
        )

        state.moveCursorTo(targetCursor)
        advanceUntilIdle()

        assertThat(state.signatureHelpUiState).isEqualTo(SignatureHelpUiState.Hidden)
        job.cancel()
    }

    @Test
    fun requestHover_shouldDismissCompletionAndSignatureHelpBeforeShowingHover() = runTest {
        val state = createState("pri")
        state.moveCursorTo(3)
        val completionItems = listOf(
            EditorCompletionItem("print"),
            EditorCompletionItem("private")
        )
        state.seedVisibleCompletion(
            items = completionItems,
            selectedIndex = 0,
            requestId = 7L
        )
        state.seedVisibleSignatureHelp(result = sampleSignatureHelpResult())
        state.onRequestHover = { "hover docs" }
        val controller = createController(state, this)

        controller.requestHover()
        advanceUntilIdle()

        assertThat(state.showCompletion).isFalse()
        assertThat(state.completionUiState).isEqualTo(CompletionUiState.Hidden)
        assertThat(state.signatureHelpUiState).isEqualTo(SignatureHelpUiState.Hidden)
        assertThat(state.hoverUiState).isEqualTo(HoverUiState.Visible("hover docs"))
    }

    @Test
    fun requestManualCompletion_shouldDismissHoverButKeepVisibleSignatureHelp() = runTest {
        val state = createState("pri")
        state.moveCursorTo(3)
        state.seedVisibleHover()
        state.seedVisibleSignatureHelp(result = sampleSignatureHelpResult())
        state.onRequestCompletion = { _, _ ->
            EditorCompletionFetchResult.Success(
                listOf(
                    EditorCompletionItem("print"),
                    EditorCompletionItem("private")
                )
            )
        }
        val controller = createController(state, this)

        controller.requestManualCompletion()
        advanceUntilIdle()

        assertThat(state.hoverUiState).isEqualTo(HoverUiState.Hidden)
        assertThat(state.signatureHelpUiState)
            .isInstanceOf(SignatureHelpUiState.Visible::class.java)
        assertThat(state.showCompletion).isTrue()
        assertThat(state.completionItems.map { it.label })
            .containsExactly("print", "private")
            .inOrder()
    }

    @Test
    fun requestManualSignatureHelp_shouldDismissHoverButKeepVisibleCompletion() = runTest {
        val state = createState("pri")
        state.moveCursorTo(3)
        val completionItems = listOf(
            EditorCompletionItem("print"),
            EditorCompletionItem("private")
        )
        state.seedVisibleCompletion(
            items = completionItems,
            selectedIndex = 1,
            requestId = 9L
        )
        state.seedVisibleHover()
        state.onRequestSignatureHelp = { sampleSignatureHelpResult() }
        val controller = createController(state, this)

        controller.requestManualSignatureHelp()
        advanceUntilIdle()

        assertThat(state.hoverUiState).isEqualTo(HoverUiState.Hidden)
        assertThat(state.showCompletion).isTrue()
        assertThat(state.completionUiState)
            .isInstanceOf(CompletionUiState.Visible::class.java)
        assertThat(state.signatureHelpUiState)
            .isInstanceOf(SignatureHelpUiState.Visible::class.java)
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

    private fun createMarkedState(markedText: String): Triple<EditorState, Int, Int> {
        val firstCursor = markedText.indexOf('|')
        check(firstCursor >= 0) { "first cursor marker '|' is required" }
        val secondCursor = markedText.indexOf('|', startIndex = firstCursor + 1)
        check(secondCursor >= 0) { "second cursor marker '|' is required" }
        val normalizedText = buildString(markedText.length - 2) {
            markedText.forEachIndexed { index, current ->
                if (index != firstCursor && index != secondCursor) {
                    append(current)
                }
            }
        }
        return Triple(
            createState(normalizedText),
            firstCursor,
            secondCursor - 1
        )
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

    private fun launchEventBridge(
        scope: TestScope,
        state: EditorState,
        controller: EditorInteractionController,
        allowAutoSignatureHelpRefresh: Boolean
    ): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        state.events.collect { event ->
            controller.onEditorEvent(
                event = event,
                allowAutoSignatureHelpRefresh = allowAutoSignatureHelpRefresh
            )
        }
    }

    private fun sampleSignatureHelpResult(): SignatureHelpResult = SignatureHelpResult(
        signatures = listOf("print(String value)"),
        activeSignature = 0,
        activeParameter = 0
    )
}
