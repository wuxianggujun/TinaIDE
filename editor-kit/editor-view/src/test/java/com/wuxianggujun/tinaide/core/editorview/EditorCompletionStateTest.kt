package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class EditorCompletionStateTest {

    @Test
    fun requestCompletion_shouldFilterByCursorPrefix() = runTest {
        val buffer = RopeTextBuffer().apply { insert(0, "pri") }
        val state = EditorState(buffer)
        state.moveCursorTo(3) // "pri" → offset 3
        state.onRequestCompletion = { _, _ ->
            EditorCompletionFetchResult.Success(
                listOf(
                    EditorCompletionItem(label = "print"),
                    EditorCompletionItem(label = "private"),
                    EditorCompletionItem(label = "class")
                )
            )
        }

        state.requestCompletion()

        assertThat(state.showCompletion).isTrue()
        assertThat(state.completionItems.map { it.label })
            .containsExactly("print", "private")
            .inOrder()
        assertThat(state.completionSelectedIndex).isEqualTo(0)
        assertThat(state.completionQuery).isEqualTo("pri")
    }

    @Test
    fun moveCompletionSelection_shouldWrapWithinVisibleItems() {
        val state = EditorState(RopeTextBuffer())
        state.seedVisibleCompletion(
            items = listOf(
                EditorCompletionItem("one"),
                EditorCompletionItem("two"),
                EditorCompletionItem("three")
            )
        )

        state.moveCompletionSelection(-1)
        assertThat(state.completionSelectedIndex).isEqualTo(2)

        state.moveCompletionSelection(1)
        assertThat(state.completionSelectedIndex).isEqualTo(0)
    }

    @Test
    fun applySelectedCompletion_shouldApplyCurrentSelection() {
        val buffer = RopeTextBuffer().apply { insert(0, "pri") }
        val state = EditorState(buffer)
        state.moveCursorTo(3)
        state.seedVisibleCompletion(
            items = listOf(
                EditorCompletionItem(label = "print"),
                EditorCompletionItem(label = "private")
            ),
            selectedIndex = 1
        )

        val applied = state.applySelectedCompletion()

        assertThat(applied).isTrue()
        assertThat(state.textBuffer.substring(0, state.textBuffer.length)).isEqualTo("private")
        assertThat(state.showCompletion).isFalse()
        assertThat(state.completionItems).isEmpty()
    }

    @Test
    fun applySelectedCompletion_shouldUseLspTextEditRange() {
        val buffer = RopeTextBuffer().apply { insert(0, "std::vec") }
        val state = EditorState(buffer)
        state.moveCursorTo(8) // "std::vec" → offset 8
        state.seedVisibleCompletion(
            items = listOf(
                EditorCompletionItem(
                    label = "vector",
                    insertText = "vector",
                    textEdit = EditorCompletionTextEdit(
                        startLine = 0,
                        startColumn = 5,
                        endLine = 0,
                        endColumn = 8,
                        newText = "vector"
                    )
                )
            ),
            query = "vec"
        )

        val applied = state.applySelectedCompletion()

        assertThat(applied).isTrue()
        assertThat(state.textBuffer.substring(0, state.textBuffer.length)).isEqualTo("std::vector")
        assertThat(state.cursorOffset).isEqualTo(11) // "std::vector" length
        assertThat(state.showCompletion).isFalse()
    }

    @Test
    fun applySelectedCompletion_shouldExpandStaleLspTextEditToCurrentMatchingPrefix() {
        val buffer = RopeTextBuffer().apply { insert(0, "op") }
        val state = EditorState(buffer)
        state.moveCursorTo(2)
        state.seedVisibleCompletion(
            items = listOf(
                EditorCompletionItem(
                    label = "operator",
                    insertText = "operator",
                    textEdit = EditorCompletionTextEdit(
                        startLine = 0,
                        startColumn = 0,
                        endLine = 0,
                        endColumn = 1,
                        newText = "operator"
                    ),
                    isLsp = true
                )
            ),
            query = "o"
        )

        val applied = state.applySelectedCompletion()

        assertThat(applied).isTrue()
        assertThat(state.textBuffer.substring(0, state.textBuffer.length)).isEqualTo("operator")
        assertThat(state.cursorOffset).isEqualTo("operator".length)
        assertThat(state.showCompletion).isFalse()
    }

    @Test
    fun applySelectedCompletion_shouldRejectStaleLspTextEditWhenPrefixDoesNotMatch() {
        val buffer = RopeTextBuffer().apply { insert(0, "xp") }
        val state = EditorState(buffer)
        state.moveCursorTo(2)
        state.seedVisibleCompletion(
            items = listOf(
                EditorCompletionItem(
                    label = "operator",
                    insertText = "operator",
                    textEdit = EditorCompletionTextEdit(
                        startLine = 0,
                        startColumn = 0,
                        endLine = 0,
                        endColumn = 1,
                        newText = "operator"
                    ),
                    isLsp = true
                )
            ),
            query = "x"
        )

        val applied = state.applySelectedCompletion()

        assertThat(applied).isTrue()
        assertThat(state.textBuffer.substring(0, state.textBuffer.length)).isEqualTo("xp")
        assertThat(state.cursorOffset).isEqualTo(2)
        assertThat(state.showCompletion).isFalse()
    }

    @Test
    fun applySelectedCompletion_shouldRejectStaleLspTextEditWhenCurrentRangePrefixDoesNotMatch() {
        val buffer = RopeTextBuffer().apply { insert(0, "xp") }
        val state = EditorState(buffer)
        state.moveCursorTo(1)
        state.seedVisibleCompletion(
            items = listOf(
                EditorCompletionItem(
                    label = "operator",
                    insertText = "operator",
                    textEdit = EditorCompletionTextEdit(
                        startLine = 0,
                        startColumn = 0,
                        endLine = 0,
                        endColumn = 1,
                        newText = "operator"
                    ),
                    isLsp = true
                )
            ),
            query = "x"
        )

        val applied = state.applySelectedCompletion()

        assertThat(applied).isTrue()
        assertThat(state.textBuffer.substring(0, state.textBuffer.length)).isEqualTo("xp")
        assertThat(state.cursorOffset).isEqualTo(1)
        assertThat(state.showCompletion).isFalse()
    }

    @Test
    fun applySelectedCompletion_shouldRejectStaleLspTextEditWhenCursorMovedToAnotherLine() {
        val buffer = RopeTextBuffer().apply { insert(0, "op\n") }
        val state = EditorState(buffer)
        state.moveCursorTo(buffer.positionToOffset(1, 0))
        state.seedVisibleCompletion(
            items = listOf(
                EditorCompletionItem(
                    label = "operator",
                    insertText = "operator",
                    textEdit = EditorCompletionTextEdit(
                        startLine = 0,
                        startColumn = 0,
                        endLine = 0,
                        endColumn = 1,
                        newText = "operator"
                    ),
                    isLsp = true
                )
            ),
            query = "o"
        )

        val applied = state.applySelectedCompletion()

        assertThat(applied).isTrue()
        assertThat(state.textBuffer.substring(0, state.textBuffer.length)).isEqualTo("op\n")
        assertThat(state.cursorOffset).isEqualTo(buffer.positionToOffset(1, 0))
        assertThat(state.showCompletion).isFalse()
    }

    @Test
    fun applySelectedCompletion_shouldExpandStaleLspTextEditForSnippetCompletion() {
        val buffer = RopeTextBuffer().apply { insert(0, "op") }
        val state = EditorState(buffer)
        state.moveCursorTo(2)
        state.seedVisibleCompletion(
            items = listOf(
                EditorCompletionItem(
                    label = "operator",
                    insertText = "operator",
                    snippetText = "operator",
                    textEdit = EditorCompletionTextEdit(
                        startLine = 0,
                        startColumn = 0,
                        endLine = 0,
                        endColumn = 1,
                        newText = "operator"
                    ),
                    isLsp = true
                )
            ),
            query = "o"
        )

        val applied = state.applySelectedCompletion()

        assertThat(applied).isTrue()
        assertThat(state.textBuffer.substring(0, state.textBuffer.length)).isEqualTo("operator")
        assertThat(state.cursorOffset).isEqualTo("operator".length)
        assertThat(state.showCompletion).isFalse()
    }

    @Test
    fun applySelectedCompletion_shouldApplyAdditionalTextEditsAndKeepCursorOnPrimaryEdit() {
        val originalText = "int main() {\n    pri\n}\n"
        val completedText = "#include <stdio.h>\nint main() {\n    printf\n}\n"
        val buffer = RopeTextBuffer().apply {
            insert(0, originalText)
        }
        val state = EditorState(buffer)
        // "int main() {\n    pri" → line 1 col 7 → offset = 14 + 7 = 21
        val cursorBefore = buffer.positionToOffset(1, 7)
        state.moveCursorTo(cursorBefore)
        state.seedVisibleCompletion(
            items = listOf(
                EditorCompletionItem(
                    label = "printf",
                    insertText = "printf",
                    textEdit = EditorCompletionTextEdit(
                        startLine = 1,
                        startColumn = 4,
                        endLine = 1,
                        endColumn = 7,
                        newText = "printf"
                    ),
                    additionalTextEdits = listOf(
                        EditorCompletionTextEdit(
                            startLine = 0,
                            startColumn = 0,
                            endLine = 0,
                            endColumn = 0,
                            newText = "#include <stdio.h>\n"
                        )
                    )
                )
            )
        )

        val applied = state.applySelectedCompletion()

        assertThat(applied).isTrue()
        assertThat(state.textBuffer.substring(0, state.textBuffer.length)).isEqualTo(completedText)
        // cursor at "printf" end = line 2 col 10
        val cursorAfter = state.textBuffer.positionToOffset(2, 10)
        assertThat(state.cursorOffset).isEqualTo(cursorAfter)
        assertThat(state.showCompletion).isFalse()

        assertThat(state.undo()).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo(originalText)
        assertThat(state.cursorOffset).isEqualTo(cursorBefore)

        assertThat(state.redo()).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo(completedText)
        assertThat(state.cursorOffset).isEqualTo(cursorAfter)
    }

    @Test
    fun applySelectedSnippetCompletion_shouldApplyAdditionalEditsAndUndoAsOneStep() {
        val originalText = "int main() {\n    pri\n}\n"
        val completedText = "#include <stdio.h>\nint main() {\n    printf(value)\n}\n"
        val buffer = RopeTextBuffer().apply { insert(0, originalText) }
        val state = EditorState(buffer)
        val cursorBefore = buffer.positionToOffset(1, 7)
        state.moveCursorTo(cursorBefore)
        state.seedVisibleCompletion(
            items = listOf(
                EditorCompletionItem(
                    label = "printf",
                    insertText = "printf",
                    snippetText = "printf(\${1:value})",
                    textEdit = EditorCompletionTextEdit(
                        startLine = 1,
                        startColumn = 4,
                        endLine = 1,
                        endColumn = 7,
                        newText = "printf"
                    ),
                    additionalTextEdits = listOf(
                        EditorCompletionTextEdit(
                            startLine = 0,
                            startColumn = 0,
                            endLine = 0,
                            endColumn = 0,
                            newText = "#include <stdio.h>\n"
                        )
                    )
                )
            )
        )

        assertThat(state.applySelectedCompletion()).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo(completedText)
        val cursorAfter = state.textBuffer.positionToOffset(2, 16)
        assertThat(state.cursorOffset).isEqualTo(cursorAfter)
        assertThat(state.activeSnippetSession).isNotNull()

        assertThat(state.undo()).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo(originalText)
        assertThat(state.cursorOffset).isEqualTo(cursorBefore)

        assertThat(state.redo()).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo(completedText)
        assertThat(state.cursorOffset).isEqualTo(cursorAfter)
    }

    @Test
    fun applySelectedCompletion_shouldRejectAllEditsWhenAdditionalRangeIsInvalid() {
        val originalText = "pri"
        val state = EditorState(RopeTextBuffer(originalText))
        state.moveCursorTo(originalText.length)
        state.seedVisibleCompletion(
            items = listOf(
                EditorCompletionItem(
                    label = "printf",
                    textEdit = EditorCompletionTextEdit(0, 0, 0, 3, "printf"),
                    additionalTextEdits = listOf(
                        EditorCompletionTextEdit(99, 0, 99, 0, "#include <stdio.h>\n")
                    )
                )
            )
        )

        assertThat(state.applySelectedCompletion()).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo(originalText)
        assertThat(state.cursorOffset).isEqualTo(originalText.length)
        assertThat(state.showCompletion).isFalse()
        assertThat(state.textBuffer.canUndo()).isFalse()
    }

    @Test
    fun applySelectedCompletion_shouldRejectOverlappingTextEdits() {
        val originalText = "pri"
        val state = EditorState(RopeTextBuffer(originalText))
        state.moveCursorTo(originalText.length)
        state.seedVisibleCompletion(
            items = listOf(
                EditorCompletionItem(
                    label = "printf",
                    textEdit = EditorCompletionTextEdit(0, 0, 0, 3, "printf"),
                    additionalTextEdits = listOf(
                        EditorCompletionTextEdit(0, 1, 0, 2, "R")
                    )
                )
            )
        )

        assertThat(state.applySelectedCompletion()).isTrue()
        assertThat(state.textBuffer.toString()).isEqualTo(originalText)
        assertThat(state.cursorOffset).isEqualTo(originalText.length)
        assertThat(state.showCompletion).isFalse()
        assertThat(state.textBuffer.canUndo()).isFalse()
    }

    @Test
    fun dismissCompletion_shouldResetCompletionUiState() {
        val state = EditorState(RopeTextBuffer())
        state.seedVisibleCompletion(
            items = listOf(EditorCompletionItem("hello")),
            query = "hel"
        )

        state.dismissCompletion()

        assertThat(state.showCompletion).isFalse()
        assertThat(state.completionItems).isEmpty()
        assertThat(state.completionSelectedIndex).isEqualTo(-1)
        assertThat(state.completionQuery).isEmpty()
    }

    @Test
    fun requestCompletion_shouldRefilterFallbackItemsOnTransientFailure() = runTest {
        val buffer = RopeTextBuffer().apply { insert(0, "pri") }
        val state = EditorState(buffer)
        state.moveCursorTo(3)
        state.seedVisibleCompletion(
            items = listOf(
                EditorCompletionItem("print"),
                EditorCompletionItem("private"),
                EditorCompletionItem("class")
            )
        )
        state.onRequestCompletion = { _, _ ->
            EditorCompletionFetchResult.TransientFailure("lsp_timeout")
        }

        state.requestCompletion()

        assertThat(state.showCompletion).isTrue()
        assertThat(state.completionItems.map { it.label })
            .containsExactly("print", "private")
            .inOrder()
        assertThat(state.completionSelectedIndex).isEqualTo(0)
        assertThat(state.completionQuery).isEqualTo("pri")
    }

    @Test
    fun requestCompletion_shouldHideFallbackWhenTransientFailureNoLongerMatchesQuery() = runTest {
        val buffer = RopeTextBuffer().apply { insert(0, "xyz") }
        val state = EditorState(buffer)
        state.moveCursorTo(3)
        state.seedVisibleCompletion(
            items = listOf(
                EditorCompletionItem("print"),
                EditorCompletionItem("private")
            )
        )
        state.onRequestCompletion = { _, _ ->
            EditorCompletionFetchResult.TransientFailure("lsp_timeout")
        }

        state.requestCompletion()

        assertThat(state.showCompletion).isFalse()
        assertThat(state.completionItems).isEmpty()
        assertThat(state.completionSelectedIndex).isEqualTo(-1)
        assertThat(state.completionQuery).isEqualTo("xyz")
    }

    @Test
    fun requestCompletion_shouldKeepPreviouslySelectedItemWhenStillPresent() = runTest {
        val buffer = RopeTextBuffer().apply { insert(0, "pr") }
        val state = EditorState(buffer)
        state.moveCursorTo(2) // "pr" → offset 2
        state.seedVisibleCompletion(
            items = listOf(
                EditorCompletionItem("print"),
                EditorCompletionItem("private"),
                EditorCompletionItem("protected")
            ),
            query = "pr",
            selectedIndex = 1
        )
        state.onRequestCompletion = { _, _ ->
            EditorCompletionFetchResult.Success(
                listOf(
                    EditorCompletionItem("private"),
                    EditorCompletionItem("print"),
                    EditorCompletionItem("println")
                )
            )
        }

        state.requestCompletion()

        assertThat(state.completionItems.map { it.label })
            .containsExactly("print", "println", "private")
            .inOrder()
        assertThat(state.completionSelectedIndex).isEqualTo(2)
        assertThat(state.completionItems[state.completionSelectedIndex].label).isEqualTo("private")
    }

    @Test
    fun requestCompletion_shouldPreserveSelectedIndexInLoadingState() = runTest {
        val buffer = RopeTextBuffer().apply { insert(0, "pri") }
        val state = EditorState(buffer)
        state.moveCursorTo(3)
        state.seedVisibleCompletion(
            items = listOf(
                EditorCompletionItem("print"),
                EditorCompletionItem("private"),
                EditorCompletionItem("protected")
            ),
            selectedIndex = 1
        )
        val response = CompletableDeferred<EditorCompletionFetchResult>()
        state.onRequestCompletion = { _, _ -> response.await() }

        val requestJob = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            state.requestCompletion()
        }

        val loadingState = state.completionUiState as CompletionUiState.Loading
        assertThat(loadingState.previousItems.map { it.label })
            .containsExactly("print", "private", "protected")
            .inOrder()
        assertThat(loadingState.query).isEqualTo("pri")
        assertThat(loadingState.selectedIndex).isEqualTo(1)
        assertThat(state.completionItems.map { it.label })
            .containsExactly("print", "private", "protected")
            .inOrder()
        assertThat(state.completionQuery).isEqualTo("pri")
        assertThat(state.completionSelectedIndex).isEqualTo(1)

        assertThat(state.moveCompletionSelection(1)).isTrue()
        val updatedLoadingState = state.completionUiState as CompletionUiState.Loading
        assertThat(updatedLoadingState.selectedIndex).isEqualTo(2)
        assertThat(state.completionSelectedIndex).isEqualTo(2)

        response.complete(EditorCompletionFetchResult.TransientFailure("timeout"))
        requestJob.join()
    }

    @Test
    fun requestCompletion_shouldPreferExactKeywordOverLongerConstantPrefix() = runTest {
        val buffer = RopeTextBuffer().apply { insert(0, "int") }
        val state = EditorState(buffer)
        state.moveCursorTo(3)
        state.onRequestCompletion = { _, _ ->
            EditorCompletionFetchResult.Success(
                listOf(
                    EditorCompletionItem(
                        label = "INT16_C",
                        kind = EditorCompletionKind.CONSTANT
                    ),
                    EditorCompletionItem(
                        label = "int",
                        kind = EditorCompletionKind.KEYWORD
                    ),
                    EditorCompletionItem(
                        label = "INT32_C",
                        kind = EditorCompletionKind.CONSTANT
                    )
                )
            )
        }

        state.requestCompletion()

        assertThat(state.completionItems.map { it.label })
            .containsAtLeast("int", "INT16_C", "INT32_C")
        assertThat(state.completionItems.first().label).isEqualTo("int")
    }

    @Test
    fun requestCompletion_shouldRespectCompletionCaseSensitiveConfig() = runTest {
        val buffer = RopeTextBuffer().apply { insert(0, "int") }
        val state = EditorState(
            textBuffer = buffer,
            config = EditorConfig(completionCaseSensitive = true)
        )
        state.moveCursorTo(3)
        state.onRequestCompletion = { _, _ ->
            EditorCompletionFetchResult.Success(
                listOf(
                    EditorCompletionItem(
                        label = "INT16_C",
                        kind = EditorCompletionKind.CONSTANT
                    ),
                    EditorCompletionItem(
                        label = "int",
                        kind = EditorCompletionKind.KEYWORD
                    ),
                    EditorCompletionItem(
                        label = "Integer",
                        kind = EditorCompletionKind.CLASS
                    )
                )
            )
        }

        state.requestCompletion()

        assertThat(state.completionItems.map { it.label })
            .containsExactly("int")
    }

    @Test
    fun requestCompletion_shouldNotUseFilterTextToBypassCaseSensitiveMatching() = runTest {
        val buffer = RopeTextBuffer().apply { insert(0, "int") }
        val state = EditorState(
            textBuffer = buffer,
            config = EditorConfig(completionCaseSensitive = true)
        )
        state.moveCursorTo(3)
        state.onRequestCompletion = { _, _ ->
            EditorCompletionFetchResult.Success(
                listOf(
                    EditorCompletionItem(
                        label = "INT16_C",
                        kind = EditorCompletionKind.CONSTANT,
                        filterText = "int16_c"
                    ),
                    EditorCompletionItem(
                        label = "int",
                        kind = EditorCompletionKind.KEYWORD
                    )
                )
            )
        }

        state.requestCompletion()

        assertThat(state.completionItems.map { it.label })
            .containsExactly("int")
    }

    @Test
    fun requestCompletion_shouldCollapseSameNamedNonCallableItemsForDisplay() = runTest {
        val buffer = RopeTextBuffer().apply { insert(0, "int") }
        val state = EditorState(buffer)
        state.moveCursorTo(3)
        state.onRequestCompletion = { _, _ ->
            EditorCompletionFetchResult.Success(
                listOf(
                    EditorCompletionItem(
                        label = "int",
                        kind = EditorCompletionKind.KEYWORD,
                        detail = "C/C++ type"
                    ),
                    EditorCompletionItem(
                        label = "int",
                        kind = EditorCompletionKind.KEYWORD,
                        insertText = "int "
                    ),
                    EditorCompletionItem(
                        label = "intValue",
                        kind = EditorCompletionKind.VARIABLE
                    )
                )
            )
        }

        state.requestCompletion()

        assertThat(state.completionItems.map { it.label })
            .containsExactly("int", "intValue")
            .inOrder()
    }

    @Test
    fun requestCompletion_shouldKeepCallableOverloadsVisibleAfterDisplayDedup() = runTest {
        val buffer = RopeTextBuffer().apply { insert(0, "pri") }
        val state = EditorState(buffer)
        state.moveCursorTo(3)
        state.onRequestCompletion = { _, _ ->
            EditorCompletionFetchResult.Success(
                listOf(
                    EditorCompletionItem(
                        label = "printf",
                        kind = EditorCompletionKind.FUNCTION,
                        detail = "printf(const char* format, ...)"
                    ),
                    EditorCompletionItem(
                        label = "printf",
                        kind = EditorCompletionKind.FUNCTION,
                        detail = "printf(const wchar_t* format, ...)"
                    )
                )
            )
        }

        state.requestCompletion()

        assertThat(state.completionItems).hasSize(2)
        assertThat(state.completionItems.map { it.detail })
            .containsExactly(
                "printf(const char* format, ...)",
                "printf(const wchar_t* format, ...)"
            )
            .inOrder()
    }
}
