package com.wuxianggujun.tinaide.core.editorview

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.wuxianggujun.tinaide.core.textengine.TextScanKernel

internal fun handleEditorShortcut(
    event: KeyEvent,
    state: EditorState,
    onCopy: (String) -> Unit,
    onCut: (String) -> Unit,
    readPasteText: () -> String?,
    onAfterTextEdit: () -> Unit,
    onRequestCompletion: () -> Unit,
    onRequestSignatureHelp: () -> Unit,
    onCycleSignatureHelp: (delta: Int) -> Boolean,
    onCompletionNavigate: (delta: Int) -> Boolean,
    onApplySelectedCompletion: () -> Boolean,
    onDismissCompletion: () -> Unit,
    onDismissSignatureHelp: () -> Unit,
    onIncreaseFont: () -> Unit,
    onDecreaseFont: () -> Unit,
    onRequestContextMenu: () -> Unit = {},
    onBeforeTextEdit: () -> Unit = {}
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val ctrlShortcutPressed = event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed
    val altShortcutPressed =
        event.isAltPressed && !event.isCtrlPressed && !event.isMetaPressed && !event.isShiftPressed
    if (event.requestsKeyboardContextMenu()) {
        onRequestContextMenu()
        return true
    }
    if (
        state.signatureHelpUiState !is SignatureHelpUiState.Hidden &&
        event.isAltPressed &&
        !event.isCtrlPressed &&
        !event.isMetaPressed
    ) {
        when (event.key) {
            Key.DirectionUp -> return onCycleSignatureHelp(-1)
            Key.DirectionDown -> return onCycleSignatureHelp(1)
            else -> Unit
        }
    }

    val completionVisible = when (val uiState = state.completionUiState) {
        is CompletionUiState.Visible -> uiState.items.isNotEmpty()
        is CompletionUiState.Loading -> uiState.previousItems.isNotEmpty()
        CompletionUiState.Hidden -> false
    }
    if (completionVisible) {
        if (event.isEditorEnterKey()) {
            onBeforeTextEdit()
            return onApplySelectedCompletion()
        }
        when (event.key) {
            Key.DirectionDown -> return onCompletionNavigate(1)
            Key.DirectionUp -> return onCompletionNavigate(-1)
            Key.Tab -> {
                onBeforeTextEdit()
                return onApplySelectedCompletion()
            }
            Key.Escape -> {
                onDismissCompletion()
                return true
            }

            else -> Unit
        }
    }

    if (event.key == Key.Escape && state.activeSnippetSession != null) {
        state.cancelSnippet()
        return true
    }

    if (event.key == Key.Escape && state.signatureHelpUiState !is SignatureHelpUiState.Hidden) {
        onDismissSignatureHelp()
        return true
    }

    if (!event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed) {
        if (event.isEditorEnterKey()) {
            onBeforeTextEdit()
            return applySmartInsertion(
                state = state,
                replacement = "\n",
                onAfterTextEdit = onAfterTextEdit
            )
        }

        when (event.key) {
            Key.Tab -> {
                // Shift+Tab：snippet 会话内退回上一个 tabstop
                if (event.isShiftPressed && state.activeSnippetSession != null) {
                    return state.retreatSnippet()
                }
                // Tab：snippet 会话内前进到下一个 tabstop
                if (!event.isShiftPressed && state.activeSnippetSession != null) {
                    return state.advanceSnippet()
                }

                val hasSelection = state.selectionRange?.isEmpty == false
                if (event.isShiftPressed) {
                    onBeforeTextEdit()
                    val changed = editorIndentOrOutdentSelectionByTab(
                        state = state,
                        outdent = true
                    )
                    if (changed) onAfterTextEdit()
                    return true
                }

                if (hasSelection) {
                    onBeforeTextEdit()
                    val changed = editorIndentOrOutdentSelectionByTab(
                        state = state,
                        outdent = false
                    )
                    if (changed) onAfterTextEdit()
                    return true
                }

                onBeforeTextEdit()
                return applySmartInsertion(
                    state = state,
                    replacement = "\t",
                    onAfterTextEdit = onAfterTextEdit
                )
            }

            Key.Backspace -> {
                onBeforeTextEdit()
                state.backspace()
                onAfterTextEdit()
                return true
            }

            Key.Delete -> {
                onBeforeTextEdit()
                state.deleteForward()
                onAfterTextEdit()
                return true
            }

            Key.DirectionLeft -> {
                state.moveLeft(extendSelection = event.isShiftPressed)
                onAfterTextEdit()
                return true
            }

            Key.DirectionRight -> {
                state.moveRight(extendSelection = event.isShiftPressed)
                onAfterTextEdit()
                return true
            }

            Key.DirectionUp -> {
                state.moveUp(extendSelection = event.isShiftPressed)
                onAfterTextEdit()
                return true
            }

            Key.DirectionDown -> {
                state.moveDown(extendSelection = event.isShiftPressed)
                onAfterTextEdit()
                return true
            }

            Key.MoveHome -> {
                moveToLineEdge(
                    state = state,
                    toEnd = false,
                    extendSelection = event.isShiftPressed
                )
                onAfterTextEdit()
                return true
            }

            Key.MoveEnd -> {
                moveToLineEdge(
                    state = state,
                    toEnd = true,
                    extendSelection = event.isShiftPressed
                )
                onAfterTextEdit()
                return true
            }

            Key.PageUp -> {
                movePageByVisualLines(
                    state = state,
                    direction = -1,
                    extendSelection = event.isShiftPressed
                )
                onAfterTextEdit()
                return true
            }

            Key.PageDown -> {
                movePageByVisualLines(
                    state = state,
                    direction = 1,
                    extendSelection = event.isShiftPressed
                )
                onAfterTextEdit()
                return true
            }

            else -> Unit
        }
    }
    return when {
        altShortcutPressed && event.key == Key.DirectionUp -> {
            onBeforeTextEdit()
            val changed = moveSelectedLines(
                state = state,
                direction = -1
            )
            if (changed) onAfterTextEdit()
            true
        }

        altShortcutPressed && event.key == Key.DirectionDown -> {
            onBeforeTextEdit()
            val changed = moveSelectedLines(
                state = state,
                direction = 1
            )
            if (changed) onAfterTextEdit()
            true
        }

        ctrlShortcutPressed && event.key == Key.DirectionLeft -> {
            moveCursorToWithOptionalSelection(
                state = state,
                targetOffset = previousWordBoundaryOffset(state),
                extendSelection = event.isShiftPressed
            )
            onAfterTextEdit()
            true
        }

        ctrlShortcutPressed && event.key == Key.DirectionRight -> {
            moveCursorToWithOptionalSelection(
                state = state,
                targetOffset = nextWordBoundaryOffset(state),
                extendSelection = event.isShiftPressed
            )
            onAfterTextEdit()
            true
        }

        ctrlShortcutPressed && event.key == Key.MoveHome -> {
            moveCursorToWithOptionalSelection(
                state = state,
                targetOffset = 0,
                extendSelection = event.isShiftPressed
            )
            onAfterTextEdit()
            true
        }

        ctrlShortcutPressed && event.key == Key.MoveEnd -> {
            moveCursorToWithOptionalSelection(
                state = state,
                targetOffset = state.textBuffer.length,
                extendSelection = event.isShiftPressed
            )
            onAfterTextEdit()
            true
        }

        ctrlShortcutPressed && event.key == Key.Backspace -> {
            onBeforeTextEdit()
            deleteWordRange(
                state = state,
                startOffset = previousWordBoundaryOffset(state),
                endOffset = state.cursorOffset
            )
            onAfterTextEdit()
            true
        }

        ctrlShortcutPressed && event.key == Key.Delete -> {
            onBeforeTextEdit()
            deleteWordRange(
                state = state,
                startOffset = state.cursorOffset,
                endOffset = nextWordBoundaryOffset(state)
            )
            onAfterTextEdit()
            true
        }

        ctrlShortcutPressed && event.isShiftPressed && event.key == Key.D -> {
            onBeforeTextEdit()
            val changed = duplicateSelectedLines(state)
            if (changed) onAfterTextEdit()
            changed
        }

        ctrlShortcutPressed && !event.isShiftPressed && event.key == Key.D -> {
            val changed = selectNextOccurrenceForCtrlD(state)
            if (changed) onAfterTextEdit()
            changed
        }

        ctrlShortcutPressed && event.key == Key.C -> {
            state.selectedText()?.let(onCopy)
            true
        }

        ctrlShortcutPressed && event.key == Key.X -> {
            state.selectedText()?.let { selected ->
                onBeforeTextEdit()
                onCut(selected)
                state.replaceSelection("")
                onAfterTextEdit()
            }
            true
        }

        ctrlShortcutPressed && event.key == Key.V -> {
            readPasteText()?.let {
                onBeforeTextEdit()
                state.replaceSelection(it)
                onAfterTextEdit()
            }
            true
        }

        ctrlShortcutPressed && event.key == Key.A -> {
            state.selectAll()
            onAfterTextEdit()
            true
        }

        ctrlShortcutPressed && event.key == Key.Z -> {
            onBeforeTextEdit()
            state.undo()
            onAfterTextEdit()
            true
        }

        ctrlShortcutPressed && event.key == Key.Y -> {
            onBeforeTextEdit()
            state.redo()
            onAfterTextEdit()
            true
        }

        ctrlShortcutPressed && event.isShiftPressed && event.key == Key.Spacebar -> {
            onRequestSignatureHelp()
            true
        }

        ctrlShortcutPressed && !event.isShiftPressed && event.key == Key.Spacebar -> {
            onRequestCompletion()
            true
        }

        ctrlShortcutPressed && event.key == Key.Equals -> {
            onIncreaseFont()
            true
        }

        ctrlShortcutPressed && event.key == Key.Minus -> {
            onDecreaseFont()
            true
        }

        else -> false
    }
}

private fun selectNextOccurrenceForCtrlD(state: EditorState): Boolean {
    val selected = state.selectedText()
    if (selected.isNullOrEmpty()) {
        val position = state.cursorPosition
        return state.selectWord(position.line, position.column)
    }
    val currentRange = state.selectionRange?.takeUnless { it.isEmpty } ?: return false
    val occurrence = findNextOccurrence(
        state = state,
        query = selected,
        currentRange = currentRange
    ) ?: return false
    state.selectRange(
        startOffset = occurrence.startOffset,
        endOffset = occurrence.endOffset
    )
    return true
}

private data class TextOccurrence(
    val startOffset: Int,
    val endOffset: Int
)

private fun findNextOccurrence(
    state: EditorState,
    query: String,
    currentRange: OffsetRange
): TextOccurrence? {
    if (query.isEmpty() || query.length > state.textBuffer.length) return null
    val fromOffset = currentRange.end.coerceIn(0, state.textBuffer.length)
    val wordQuery = query.all(TextScanKernel::isWordChar)
    val forward = findOccurrenceInRange(
        state = state,
        query = query,
        startOffset = fromOffset,
        endOffset = state.textBuffer.length,
        currentRange = currentRange,
        wholeWord = wordQuery
    )
    if (forward != null) return forward
    return findOccurrenceInRange(
        state = state,
        query = query,
        startOffset = 0,
        endOffset = fromOffset,
        currentRange = currentRange,
        wholeWord = wordQuery
    )
}

private fun findOccurrenceInRange(
    state: EditorState,
    query: String,
    startOffset: Int,
    endOffset: Int,
    currentRange: OffsetRange,
    wholeWord: Boolean
): TextOccurrence? {
    val safeStart = startOffset.coerceIn(0, state.textBuffer.length)
    val safeEnd = endOffset.coerceIn(safeStart, state.textBuffer.length)
    if (safeStart >= safeEnd) return null
    return if (wholeWord) {
        findWholeWordOccurrenceInRange(
            state = state,
            word = query,
            startOffset = safeStart,
            endOffset = safeEnd,
            currentRange = currentRange
        )
    } else {
        findExactOccurrenceInRange(
            state = state,
            query = query,
            startOffset = safeStart,
            endOffset = safeEnd,
            currentRange = currentRange
        )
    }
}

private fun findWholeWordOccurrenceInRange(
    state: EditorState,
    word: String,
    startOffset: Int,
    endOffset: Int,
    currentRange: OffsetRange
): TextOccurrence? {
    val startLine = state.textBuffer.offsetToPosition(startOffset).line
    val endLine = state.textBuffer.offsetToPosition(endOffset).line
    for (line in startLine..endLine) {
        val lineText = state.textBuffer.getLine(line)
        val lineStartOffset = state.textBuffer.getLineStart(line)
        val rangeStartColumn = if (line == startLine) {
            (startOffset - lineStartOffset).coerceIn(0, lineText.length)
        } else {
            0
        }
        val rangeEndColumn = if (line == endLine) {
            (endOffset - lineStartOffset).coerceIn(0, lineText.length)
        } else {
            lineText.length
        }
        val matches = TextScanKernel.findWholeWordMatches(lineText, word)
        for (column in matches) {
            if (column < rangeStartColumn || column + word.length > rangeEndColumn) continue
            val occurrence = TextOccurrence(
                startOffset = lineStartOffset + column,
                endOffset = lineStartOffset + column + word.length
            )
            if (!occurrence.sameRangeAs(currentRange)) return occurrence
        }
    }
    return null
}

private fun findExactOccurrenceInRange(
    state: EditorState,
    query: String,
    startOffset: Int,
    endOffset: Int,
    currentRange: OffsetRange
): TextOccurrence? {
    val documentText = state.textBuffer.substring(0, state.textBuffer.length)
    var searchIndex = startOffset
    while (searchIndex < endOffset) {
        val matchIndex = documentText.indexOf(query, startIndex = searchIndex)
        if (matchIndex < 0 || matchIndex + query.length > endOffset) return null
        val occurrence = TextOccurrence(
            startOffset = matchIndex,
            endOffset = matchIndex + query.length
        )
        if (!occurrence.sameRangeAs(currentRange)) return occurrence
        searchIndex = (matchIndex + 1).coerceAtMost(endOffset)
    }
    return null
}

private fun TextOccurrence.sameRangeAs(range: OffsetRange): Boolean = startOffset == range.start && endOffset == range.end

private fun KeyEvent.isEditorEnterKey(): Boolean = key == Key.Enter || nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER

private fun KeyEvent.requestsKeyboardContextMenu(): Boolean {
    if (isCtrlPressed || isAltPressed || isMetaPressed) return false
    val nativeKeyCode = nativeKeyEvent.keyCode
    return nativeKeyCode == AndroidKeyEvent.KEYCODE_MENU ||
        (isShiftPressed && nativeKeyCode == AndroidKeyEvent.KEYCODE_F10)
}

private fun duplicateSelectedLines(state: EditorState): Boolean {
    val block = resolveSelectedLineBlock(state)
    val blockText = state.textBuffer.substring(block.startOffset, block.endOffset)
    val duplicateText = if (
        block.endLine == state.textBuffer.lineCount - 1 &&
        !blockText.endsWith('\n')
    ) {
        "\n" + blockText
    } else {
        blockText
    }
    val cursorOffsetBeforeDuplicate = state.cursorOffset
    val changed = state.replaceRange(
        startOffset = block.endOffset,
        endOffset = block.endOffset,
        replacement = duplicateText
    )
    if (!changed) return false
    restoreDuplicatedLineSelection(
        state = state,
        selection = block.selection,
        cursorOffsetBeforeDuplicate = cursorOffsetBeforeDuplicate,
        delta = duplicateText.length
    )
    return true
}

private fun restoreDuplicatedLineSelection(
    state: EditorState,
    selection: OffsetRange?,
    cursorOffsetBeforeDuplicate: Int,
    delta: Int
) {
    if (selection != null) {
        state.startSelection(selection.anchor + delta)
        state.updateSelectionTo(selection.caret + delta)
    } else {
        state.moveCursorTo(cursorOffsetBeforeDuplicate + delta)
    }
}

private fun moveSelectedLines(
    state: EditorState,
    direction: Int
): Boolean {
    val lineCount = state.textBuffer.lineCount
    if (lineCount <= 1) return false
    val block = resolveSelectedLineBlock(state)
    return when {
        direction < 0 && block.startLine > 0 -> moveLineBlockUp(state, block)
        direction > 0 && block.endLine < lineCount - 1 -> moveLineBlockDown(state, block)
        else -> false
    }
}

private data class SelectedLineBlock(
    val startLine: Int,
    val endLine: Int,
    val startOffset: Int,
    val endOffset: Int,
    val selection: OffsetRange?
)

private fun resolveSelectedLineBlock(state: EditorState): SelectedLineBlock {
    val selection = state.selectionRange?.takeUnless { it.isEmpty }
    val startOffset = selection?.start ?: state.cursorOffset
    val endOffset = selection?.end ?: state.cursorOffset
    val startLine = state.textBuffer
        .offsetToPosition(startOffset.coerceIn(0, state.textBuffer.length))
        .line
    val rawEndLine = state.textBuffer
        .offsetToPosition(endOffset.coerceIn(0, state.textBuffer.length))
        .line
    val endLine = if (
        selection != null &&
        endOffset > startOffset &&
        rawEndLine > startLine &&
        endOffset == state.textBuffer.getLineStart(rawEndLine)
    ) {
        rawEndLine - 1
    } else {
        rawEndLine
    }.coerceIn(startLine, state.textBuffer.lineCount - 1)
    return SelectedLineBlock(
        startLine = startLine,
        endLine = endLine,
        startOffset = state.textBuffer.getLineStart(startLine),
        endOffset = lineEndIncludingSeparator(state, endLine),
        selection = selection
    )
}

private fun moveLineBlockUp(
    state: EditorState,
    block: SelectedLineBlock
): Boolean {
    val previousStart = state.textBuffer.getLineStart(block.startLine - 1)
    val previousText = state.textBuffer.substring(previousStart, block.startOffset)
    val blockText = state.textBuffer.substring(block.startOffset, block.endOffset)
    val replacement = if (blockText.endsWith('\n')) {
        blockText + previousText
    } else {
        blockText + "\n" + previousText.removeSuffix("\n")
    }
    val cursorOffsetBeforeMove = state.cursorOffset
    val changed = state.replaceRange(
        startOffset = previousStart,
        endOffset = block.endOffset,
        replacement = replacement
    )
    if (!changed) return false
    restoreMovedLineSelection(
        state = state,
        selection = block.selection,
        cursorOffsetBeforeMove = cursorOffsetBeforeMove,
        delta = previousStart - block.startOffset
    )
    return true
}

private fun moveLineBlockDown(
    state: EditorState,
    block: SelectedLineBlock
): Boolean {
    val nextEnd = lineEndIncludingSeparator(state, block.endLine + 1)
    val blockText = state.textBuffer.substring(block.startOffset, block.endOffset)
    val nextText = state.textBuffer.substring(block.endOffset, nextEnd)
    val replacement = if (nextText.endsWith('\n')) {
        nextText + blockText
    } else {
        nextText + "\n" + blockText.removeSuffix("\n")
    }
    val cursorOffsetBeforeMove = state.cursorOffset
    val changed = state.replaceRange(
        startOffset = block.startOffset,
        endOffset = nextEnd,
        replacement = replacement
    )
    if (!changed) return false
    val delta = nextText.length + if (nextText.endsWith('\n')) 0 else 1
    restoreMovedLineSelection(
        state = state,
        selection = block.selection,
        cursorOffsetBeforeMove = cursorOffsetBeforeMove,
        delta = delta
    )
    return true
}

private fun restoreMovedLineSelection(
    state: EditorState,
    selection: OffsetRange?,
    cursorOffsetBeforeMove: Int,
    delta: Int
) {
    if (selection != null) {
        state.startSelection(selection.anchor + delta)
        state.updateSelectionTo(selection.caret + delta)
    } else {
        state.moveCursorTo(cursorOffsetBeforeMove + delta)
    }
}

private fun lineEndIncludingSeparator(state: EditorState, line: Int): Int = if (line + 1 < state.textBuffer.lineCount) {
    state.textBuffer.getLineStart(line + 1)
} else {
    state.textBuffer.length
}

private enum class WordBoundaryCharKind {
    Word,
    Whitespace,
    Other
}

private fun previousWordBoundaryOffset(state: EditorState): Int {
    var target = state.cursorOffset.coerceIn(0, state.textBuffer.length)
    if (target <= 0) return 0
    while (target > 0 && charKindBefore(state, target) == WordBoundaryCharKind.Whitespace) {
        target--
    }
    if (target <= 0) return 0
    val targetKind = charKindBefore(state, target)
    while (target > 0 && charKindBefore(state, target) == targetKind) {
        target--
    }
    return target
}

private fun nextWordBoundaryOffset(state: EditorState): Int {
    var target = state.cursorOffset.coerceIn(0, state.textBuffer.length)
    val documentLength = state.textBuffer.length
    if (target >= documentLength) return documentLength
    while (target < documentLength && charKindAt(state, target) == WordBoundaryCharKind.Whitespace) {
        target++
    }
    if (target >= documentLength) return documentLength
    val targetKind = charKindAt(state, target)
    while (target < documentLength && charKindAt(state, target) == targetKind) {
        target++
    }
    return target
}

private fun deleteWordRange(
    state: EditorState,
    startOffset: Int,
    endOffset: Int
) {
    val selection = state.selectionRange
    if (selection != null && !selection.isEmpty) {
        state.replaceSelection("")
        return
    }
    val start = startOffset.coerceIn(0, state.textBuffer.length)
    val end = endOffset.coerceIn(start, state.textBuffer.length)
    if (start < end) {
        state.replaceRange(startOffset = start, endOffset = end, replacement = "")
    }
}

private fun charKindBefore(state: EditorState, offset: Int): WordBoundaryCharKind = charKindAt(state, offset - 1)

private fun charKindAt(state: EditorState, offset: Int): WordBoundaryCharKind {
    val char = state.textBuffer.charAt(offset) ?: return WordBoundaryCharKind.Whitespace
    return when {
        TextScanKernel.isWordChar(char) -> WordBoundaryCharKind.Word
        char.isWhitespace() -> WordBoundaryCharKind.Whitespace
        else -> WordBoundaryCharKind.Other
    }
}

private fun moveToLineEdge(
    state: EditorState,
    toEnd: Boolean,
    extendSelection: Boolean
) {
    val position = state.cursorPosition
    val targetColumn = if (toEnd) {
        state.textBuffer.getLine(position.line).length
    } else {
        0
    }
    moveCursorToWithOptionalSelection(
        state = state,
        targetOffset = state.textBuffer.positionToOffset(position.line, targetColumn),
        extendSelection = extendSelection
    )
}

private fun movePageByVisualLines(
    state: EditorState,
    direction: Int,
    extendSelection: Boolean
) {
    val visualLineCount = state.visualLineCount()
    if (visualLineCount <= 0) return
    val position = state.cursorPosition
    val currentVisualLine = state.visualLineForPosition(position.line, position.column)
    val pageLineCount = ((state.viewportHeightPx / state.lineHeightPx).toInt() - 1)
        .coerceAtLeast(1)
    val targetVisualLine = (
        currentVisualLine.toLong() + direction.toLong() * pageLineCount.toLong()
    ).coerceIn(0L, (visualLineCount - 1).toLong()).toInt()
    val targetLine = state.docLineForVisualLine(targetVisualLine)
    val targetLineText = state.textBuffer.getLine(targetLine)
    val targetColumn = coerceColumnToVisualLineBounds(
        column = position.column,
        startColumn = state.visualLineStartColumn(targetVisualLine),
        endColumn = state.visualLineEndColumn(targetVisualLine),
        lineLength = targetLineText.length
    )
    moveCursorToWithOptionalSelection(
        state = state,
        targetOffset = state.textBuffer.positionToOffset(targetLine, targetColumn),
        extendSelection = extendSelection
    )
}

internal fun coerceColumnToVisualLineBounds(
    column: Int,
    startColumn: Int,
    endColumn: Int,
    lineLength: Int
): Int {
    val safeLineLength = lineLength.coerceAtLeast(0)
    val minimumColumn = minOf(startColumn, endColumn).coerceIn(0, safeLineLength)
    val maximumColumn = maxOf(startColumn, endColumn).coerceIn(minimumColumn, safeLineLength)
    return column.coerceIn(minimumColumn, maximumColumn)
}

private fun moveCursorToWithOptionalSelection(
    state: EditorState,
    targetOffset: Int,
    extendSelection: Boolean
) {
    val safeTarget = targetOffset.coerceIn(0, state.textBuffer.length)
    if (!extendSelection) {
        state.moveCursorTo(safeTarget)
        return
    }
    if (state.selectionRange == null) {
        state.startSelection(state.cursorOffset)
    }
    state.updateSelectionTo(safeTarget)
}

private fun applySmartInsertion(
    state: EditorState,
    replacement: String,
    onAfterTextEdit: () -> Unit
): Boolean {
    val (startOffset, endOffset) = selectionOffsets(state)
    val resolved = EditorSmartReplacement.resolve(
        state = state,
        startOffset = startOffset,
        replacement = replacement,
        endOffset = endOffset,
    )
    val changed = editorReplaceRange(
        state = state,
        startOffset = startOffset,
        endOffset = endOffset,
        replacement = resolved.replacement,
        cursorOffsetAfterEdit = resolved.cursorOffsetAfterInsert
    )
    if (changed) onAfterTextEdit()
    return true
}

private fun selectionOffsets(state: EditorState): Pair<Int, Int> {
    val range = state.selectionRange
    if (range != null && !range.isEmpty) {
        return range.start to range.end
    }
    val cursor = state.cursorOffset.coerceIn(0, state.textBuffer.length)
    return cursor to cursor
}
