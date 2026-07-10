package com.wuxianggujun.tinaide.core.editorview

import com.wuxianggujun.tinaide.core.textengine.Position
import com.wuxianggujun.tinaide.core.textengine.TextScanKernel

internal fun editorInsert(state: EditorState, text: String) {
    if (text.isEmpty()) return
    state.traceSlowOperation("insert") {
        val selection = state.selectionRange
        val replaceStart = selection?.start ?: state.cursorOffset
        val replaceEnd = selection?.end ?: state.cursorOffset
        if (applySynchronizedSnippetGroupReplace(
                state = state,
                startOffset = replaceStart,
                endOffset = replaceEnd,
                replacement = text,
                reason = "insertSnippetGroup"
            )
        ) {
            return@traceSlowOperation
        }
        val safeStart = replaceStart.coerceIn(0, state.textBuffer.length)
        val safeEnd = replaceEnd.coerceIn(safeStart, state.textBuffer.length)
        val replacedLength = safeEnd - safeStart
        state.textBuffer.replace(safeStart, safeEnd, text)
        state.emitTextChanged(reason = "insert")
        val delta = text.length - replacedLength
        if (delta != 0) {
            state.adjustSnippetOffsets(safeStart, delta)
        }
        state.moveCursorTo(safeStart + text.length)
    }
}

private val AUTO_CLOSE_PAIR_MAP = mapOf(
    '(' to ')',
    '[' to ']',
    '{' to '}',
    '"' to '"',
    '\'' to '\''
)

private fun applySynchronizedSnippetGroupReplace(
    state: EditorState,
    startOffset: Int,
    endOffset: Int,
    replacement: String,
    reason: String
): Boolean {
    val session = state.activeSnippetSession ?: return false
    val group = session.currentGroup()
    if (group.size <= 1) return false

    val primary = session.currentPlaceholder() ?: return false
    val primaryStart = session.absoluteOffsetOf(primary)
    val primaryEnd = primaryStart + primary.length
    val safeStart = startOffset.coerceIn(0, state.textBuffer.length)
    val safeEnd = endOffset.coerceIn(safeStart, state.textBuffer.length)
    if (safeStart < primaryStart || safeEnd > primaryEnd) {
        return false
    }

    val relativeStart = safeStart - primaryStart
    val relativeEnd = safeEnd - primaryStart
    val fullSelection = relativeStart == 0 && relativeEnd == primary.length
    val updatedSession = session.applySynchronizedEdit(
        relativeStart = relativeStart,
        relativeEnd = relativeEnd,
        replacement = replacement
    ) ?: return false
    val updatedPrimary = updatedSession.currentPlaceholder() ?: return false
    val targetCursorOffset =
        updatedSession.absoluteOffsetOf(updatedPrimary) + relativeStart + replacement.length

    state.textBuffer.editTransaction(
        cursorBefore = state.cursorOffset,
        cursorAfter = { state.cursorOffset }
    ) {
        group.asReversed().forEach { placeholder ->
            val absoluteStart = session.absoluteOffsetOf(placeholder)
            val replaceStart = absoluteStart + relativeStart
            val replaceEnd = if (fullSelection) {
                absoluteStart + placeholder.length
            } else {
                absoluteStart + relativeEnd
            }
            state.textBuffer.replace(replaceStart, replaceEnd, replacement)
        }

        state.updateSnippetSession(updatedSession)
        state.moveCursorTo(targetCursorOffset)
    }

    state.emitTextChanged(reason = reason)
    return true
}

internal fun editorBackspace(state: EditorState) {
    state.traceSlowOperation("backspace") {
        val selRange = state.selectionRange
        if (selRange != null && !selRange.isEmpty) {
            if (applySynchronizedSnippetGroupReplace(
                    state = state,
                    startOffset = selRange.start,
                    endOffset = selRange.end,
                    replacement = "",
                    reason = "backspaceSelection"
                )
            ) {
                return@traceSlowOperation
            }
            val selStart = selRange.start
            val selLen = selRange.end - selStart
            deleteSelectionIfPresent(state)
            state.emitTextChanged(reason = "backspaceSelection")
            state.adjustSnippetOffsets(selStart, -selLen)
            return@traceSlowOperation
        }
        val offset = state.cursorOffset.coerceIn(0, state.textBuffer.length)
        if (offset <= 0) return@traceSlowOperation

        if (offset >= 1 && offset < state.textBuffer.length) {
            val charBefore = state.textBuffer.charAt(offset - 1)
            val charAfter = state.textBuffer.charAt(offset)
            if (charBefore != null && charAfter != null && AUTO_CLOSE_PAIR_MAP[charBefore] == charAfter) {
                if (applySynchronizedSnippetGroupReplace(
                        state = state,
                        startOffset = offset - 1,
                        endOffset = offset + 1,
                        replacement = "",
                        reason = "backspacePair"
                    )
                ) {
                    return@traceSlowOperation
                }
                state.textBuffer.delete(offset - 1, offset + 1)
                state.emitTextChanged(reason = "backspacePair")
                state.adjustSnippetOffsets(offset - 1, -2)
                state.moveCursorTo(offset - 1)
                return@traceSlowOperation
            }
        }

        val highSurrogate = state.textBuffer.charAt(offset - 2)
        val lowSurrogate = state.textBuffer.charAt(offset - 1)
        val deleteCount = if (
            highSurrogate != null && lowSurrogate != null &&
            Character.isSurrogatePair(highSurrogate, lowSurrogate)
        ) {
            2
        } else {
            1
        }
        val targetOffset = offset - deleteCount
        val targetPos = state.textBuffer.offsetToPosition(targetOffset)

        if (state.isDocLineHidden(targetPos.line)) {
            if (state.isFoldEndLineVirtuallyVisible(targetPos.line)) {
                val endLineText = state.textBuffer.getLine(targetPos.line)
                val trimStartCol = TextScanKernel
                    .scanLineWhitespace(endLineText, state.config.tabSize)
                    .leadingWhitespaceEnd
                if (targetPos.column < trimStartCol) {
                    val ownerStart = state.foldOwnerForEndLine(targetPos.line)
                    if (ownerStart >= 0) {
                        state.toggleFoldAtLine(ownerStart)
                        state.moveCursorTo(offset)
                        return@traceSlowOperation
                    }
                }
            } else {
                val ownerStart = state.foldOwnerForHiddenLine(targetPos.line)
                if (ownerStart >= 0) {
                    state.toggleFoldAtLine(ownerStart)
                    state.moveCursorTo(offset)
                    return@traceSlowOperation
                }
            }
        }

        val deleteStart = offset - deleteCount
        if (applySynchronizedSnippetGroupReplace(
                state = state,
                startOffset = deleteStart,
                endOffset = offset,
                replacement = "",
                reason = "backspace"
            )
        ) {
            return@traceSlowOperation
        }
        state.textBuffer.delete(deleteStart, offset)
        state.emitTextChanged(reason = "backspace")
        state.adjustSnippetOffsets(deleteStart, -deleteCount)

        val newOffset = offset - deleteCount
        val newPos = state.textBuffer.offsetToPosition(newOffset)
        if (state.isFoldEndLineVirtuallyVisible(newPos.line)) {
            val endLineText = state.textBuffer.getLine(newPos.line)
            val whitespaceInfo = TextScanKernel.scanLineWhitespace(endLineText, state.config.tabSize)
            val trimmedEnd = whitespaceInfo.trailingWhitespaceStart
            val foldEndBroken = trimmedEnd <= 0 || endLineText[trimmedEnd - 1] != '}'
            if (foldEndBroken) {
                val ownerStart = state.foldOwnerForEndLine(newPos.line)
                if (ownerStart >= 0) {
                    state.markFoldAsBroken(ownerStart)
                    state.toggleFoldAtLine(ownerStart)
                }
            }
        }

        state.moveCursorTo(newOffset)
    }
}

internal fun editorDeleteForward(state: EditorState) {
    state.traceSlowOperation("deleteForward") {
        val selRange = state.selectionRange
        if (selRange != null && !selRange.isEmpty) {
            if (applySynchronizedSnippetGroupReplace(
                    state = state,
                    startOffset = selRange.start,
                    endOffset = selRange.end,
                    replacement = "",
                    reason = "deleteSelection"
                )
            ) {
                return@traceSlowOperation
            }
            val selStart = selRange.start
            val selLen = selRange.end - selStart
            deleteSelectionIfPresent(state)
            state.emitTextChanged(reason = "deleteSelection")
            state.adjustSnippetOffsets(selStart, -selLen)
            return@traceSlowOperation
        }
        val offset = state.cursorOffset.coerceIn(0, state.textBuffer.length)
        if (offset >= state.textBuffer.length) return@traceSlowOperation
        // Surrogate pair: delete both code units
        val highSurrogate = state.textBuffer.charAt(offset)
        val lowSurrogate = state.textBuffer.charAt(offset + 1)
        val deleteCount = if (
            highSurrogate != null && lowSurrogate != null &&
            Character.isSurrogatePair(highSurrogate, lowSurrogate)
        ) {
            2
        } else {
            1
        }
        if (applySynchronizedSnippetGroupReplace(
                state = state,
                startOffset = offset,
                endOffset = offset + deleteCount,
                replacement = "",
                reason = "deleteForward"
            )
        ) {
            return@traceSlowOperation
        }
        state.textBuffer.delete(offset, offset + deleteCount)
        state.emitTextChanged(reason = "deleteForward")
        state.adjustSnippetOffsets(offset, -deleteCount)
    }
}

internal fun editorReplaceSelection(state: EditorState, replacement: String): Boolean {
    val range = state.selectionRange
    val hasSelection = range != null && !range.isEmpty
    val startOffset = if (hasSelection) range!!.start else state.cursorOffset
    val endOffset = if (hasSelection) range!!.end else state.cursorOffset

    val safeStart = startOffset.coerceIn(0, state.textBuffer.length)
    val safeEnd = endOffset.coerceIn(safeStart, state.textBuffer.length)
    if (safeStart == safeEnd && replacement.isEmpty()) {
        return false
    }
    if (state.textBuffer.substring(safeStart, safeEnd) == replacement) {
        state.moveCursorTo(safeStart + replacement.length)
        return false
    }
    if (applySynchronizedSnippetGroupReplace(
            state = state,
            startOffset = safeStart,
            endOffset = safeEnd,
            replacement = replacement,
            reason = "replaceSelection"
        )
    ) {
        return true
    }

    val deletedLen = safeEnd - safeStart
    state.textBuffer.replace(safeStart, safeEnd, replacement)
    state.emitTextChanged(reason = "replaceSelection")

    val delta = replacement.length - deletedLen
    if (delta != 0) {
        state.adjustSnippetOffsets(safeStart, delta)
    }

    state.moveCursorTo(safeStart + replacement.length)
    return true
}

internal fun editorReplaceRange(
    state: EditorState,
    startOffset: Int,
    endOffset: Int,
    replacement: String
): Boolean {
    return state.traceSlowOperation("replaceRange") {
        val safeStart = startOffset.coerceIn(0, state.textBuffer.length)
        val safeEnd = endOffset.coerceIn(safeStart, state.textBuffer.length)
        if (safeStart == safeEnd && replacement.isEmpty()) {
            return@traceSlowOperation false
        }
        if (state.textBuffer.substring(safeStart, safeEnd) == replacement) {
            state.moveCursorTo(safeStart + replacement.length)
            return@traceSlowOperation false
        }
        if (applySynchronizedSnippetGroupReplace(
                state = state,
                startOffset = safeStart,
                endOffset = safeEnd,
                replacement = replacement,
                reason = "replaceRange"
            )
        ) {
            return@traceSlowOperation true
        }

        val deletedLen = safeEnd - safeStart
        state.textBuffer.replace(safeStart, safeEnd, replacement)
        state.emitTextChanged(reason = "replaceRange")

        val delta = replacement.length - deletedLen
        if (delta != 0) {
            state.adjustSnippetOffsets(safeStart, delta)
        }

        state.moveCursorTo(safeStart + replacement.length)
        true
    }
}

internal fun editorUndo(state: EditorState): Boolean {
    return state.traceSlowOperation("undo") {
        val result = state.textBuffer.undo() ?: return@traceSlowOperation false
        state.cancelSnippet()
        state.emitTextChanged(reason = "undo")
        state.moveCursorTo(result.cursorOffset.coerceIn(0, state.textBuffer.length))
        true
    }
}

internal fun editorRedo(state: EditorState): Boolean {
    return state.traceSlowOperation("redo") {
        val result = state.textBuffer.redo() ?: return@traceSlowOperation false
        state.cancelSnippet()
        state.emitTextChanged(reason = "redo")
        state.moveCursorTo(result.cursorOffset.coerceIn(0, state.textBuffer.length))
        true
    }
}

internal fun editorApplyCompletion(state: EditorState, item: EditorCompletionItem) {
    val itemWithPrimaryEdit = if (item.textEdit != null) {
        item
    } else {
        item.copy(textEdit = synthesizePrimaryCompletionEdit(state, item.insertText))
    }
    val normalizedItem = itemWithPrimaryEdit.normalizeCompletionPrimaryEditForCurrentCursor(state)
        ?: run {
            state.dismissCompletion()
            return
        }
    val snippetText = normalizedItem.snippetText
    if (snippetText != null) {
        applySnippetCompletion(state, normalizedItem, snippetText)
        return
    }
    if (!applyCompletionWithTextEdits(state, normalizedItem)) {
        state.dismissCompletion()
    }
}

private fun EditorCompletionItem.normalizeCompletionPrimaryEditForCurrentCursor(
    state: EditorState
): EditorCompletionItem? {
    val edit = textEdit ?: return this
    if (!isLsp) return this
    val cursor = state.cursorPosition
    if (edit.startLine != edit.endLine) return this
    if (edit.endLine != cursor.line) return null

    val lineText = state.textBuffer.getLine(cursor.line)
    val startColumn = edit.startColumn.coerceIn(0, lineText.length)
    val oldEndColumn = edit.endColumn.coerceIn(startColumn, lineText.length)
    val cursorColumn = cursor.column.coerceIn(0, lineText.length)
    if (cursorColumn < startColumn) return null

    val currentPrefix = lineText.substring(startColumn, cursorColumn)
    if (
        currentPrefix.isNotEmpty() &&
        !matchesCurrentCompletionPrefix(
            prefix = currentPrefix,
            caseSensitive = state.config.completionCaseSensitive
        )
    ) {
        return null
    }
    if (cursorColumn <= oldEndColumn) return this

    return copy(textEdit = edit.copy(endColumn = cursorColumn))
}

private fun EditorCompletionItem.matchesCurrentCompletionPrefix(
    prefix: String,
    caseSensitive: Boolean
): Boolean {
    val ignoreCase = !caseSensitive
    return sequenceOf(label, filterText, insertText, textEdit?.newText, snippetText)
        .filterNotNull()
        .any { candidate -> candidate.startsWith(prefix, ignoreCase = ignoreCase) }
}

private fun applySnippetCompletion(
    state: EditorState,
    item: EditorCompletionItem,
    snippetText: String
) {
    val parsed = parseSnippet(snippetText)
    if (parsed.placeholders.isEmpty()) {
        // 无占位符的 snippet，当纯文本处理
        val plainItem = item.copy(
            insertText = parsed.expandedText,
            snippetText = null,
            textEdit = item.textEdit?.copy(newText = parsed.expandedText)
        )
        val normalizedItem = if (plainItem.textEdit != null) {
            plainItem
        } else {
            plainItem.copy(textEdit = synthesizePrimaryCompletionEdit(state, plainItem.insertText))
        }
        if (!applyCompletionWithTextEdits(state, normalizedItem)) {
            state.dismissCompletion()
        }
        return
    }

    // 有占位符的 snippet
    val edit = item.textEdit
        ?: synthesizePrimaryCompletionEdit(state, "")

    val expandedText = parsed.expandedText
    val resolvedEdits = resolveCompletionEdits(
        state = state,
        primaryEdit = edit.copy(newText = expandedText),
        additionalEdits = item.additionalTextEdits
    )
    if (resolvedEdits == null) {
        state.dismissCompletion()
        return
    }

    state.cancelSnippet()

    val application = state.textBuffer.editTransaction<CompletionAppliedEdits?>(
        cursorBefore = state.cursorOffset,
        cursorAfter = { state.cursorOffset }
    ) {
        val result = applyResolvedCompletionEdits(state, resolvedEdits)
            ?: return@editTransaction null
        state.dismissCompletion()
        state.startSnippetSession(
            SnippetSession(
                baseOffset = result.primaryStartOffset,
                parsed = parsed
            )
        )
        result
    } ?: run {
        state.dismissCompletion()
        return
    }

    if (application.changed) {
        state.emitTextChanged(reason = "applySnippetCompletion")
    }
}

private data class CompletionResolvedEdit(
    val startOffset: Int,
    val endOffset: Int,
    val newText: String,
    val primary: Boolean
)

private data class CompletionResolvedEdits(
    val documentVersion: Long,
    val edits: List<CompletionResolvedEdit>
)

private data class CompletionAppliedEdits(
    val changed: Boolean,
    val primaryStartOffset: Int,
    val primaryEndOffset: Int
)

private fun applyCompletionWithTextEdits(
    state: EditorState,
    item: EditorCompletionItem
): Boolean {
    val primaryEdit = item.textEdit ?: return false
    val resolvedEdits = resolveCompletionEdits(
        state = state,
        primaryEdit = primaryEdit,
        additionalEdits = item.additionalTextEdits
    ) ?: return false

    val synchronizedPrimary = resolvedEdits.edits.singleOrNull { it.primary }
    if (
        synchronizedPrimary != null &&
        resolvedEdits.edits.size == 1 &&
        state.textBuffer.version == resolvedEdits.documentVersion
    ) {
        if (applySynchronizedSnippetGroupReplace(
                state = state,
                startOffset = synchronizedPrimary.startOffset,
                endOffset = synchronizedPrimary.endOffset,
                replacement = synchronizedPrimary.newText,
                reason = "applyCompletionSnippetGroup"
            )
        ) {
            state.dismissCompletion()
            return true
        }
    }

    val application = state.textBuffer.editTransaction<CompletionAppliedEdits?>(
        cursorBefore = state.cursorOffset,
        cursorAfter = { state.cursorOffset }
    ) {
        val result = applyResolvedCompletionEdits(state, resolvedEdits)
            ?: return@editTransaction null
        state.moveCursorTo(result.primaryEndOffset.coerceIn(0, state.textBuffer.length))
        result
    } ?: return false

    if (application.changed) {
        state.emitTextChanged(reason = "applyCompletion")
    }
    state.dismissCompletion()
    return true
}

private fun applyResolvedCompletionEdits(
    state: EditorState,
    resolvedEdits: CompletionResolvedEdits
): CompletionAppliedEdits? {
    if (state.textBuffer.version != resolvedEdits.documentVersion) return null
    val documentLength = state.textBuffer.length
    if (resolvedEdits.edits.any { edit ->
            edit.startOffset !in 0..documentLength || edit.endOffset !in edit.startOffset..documentLength
        }
    ) {
        return null
    }

    val ordered = resolvedEdits.edits.sortedWith(
        compareByDescending<CompletionResolvedEdit> { it.startOffset }
            .thenByDescending { it.endOffset }
            .thenBy { it.primary }
    )

    var changed = false
    var primaryStartOffset: Int? = null
    var primaryEndOffset: Int? = null
    ordered.forEach { edit ->
        val replacedLength = edit.endOffset - edit.startOffset
        val oldText = state.textBuffer.substring(edit.startOffset, edit.endOffset)
        if (oldText != edit.newText) {
            state.textBuffer.replace(edit.startOffset, edit.endOffset, edit.newText)
            changed = true
        }

        val delta = edit.newText.length - replacedLength
        if (edit.primary) {
            primaryStartOffset = edit.startOffset
            primaryEndOffset = edit.startOffset + edit.newText.length
        } else if (primaryStartOffset != null && edit.startOffset <= primaryStartOffset!!) {
            primaryStartOffset = primaryStartOffset!! + delta
            primaryEndOffset = primaryEndOffset!! + delta
        }
    }

    return CompletionAppliedEdits(
        changed = changed,
        primaryStartOffset = primaryStartOffset ?: return null,
        primaryEndOffset = primaryEndOffset ?: return null
    )
}

private fun resolveCompletionEdits(
    state: EditorState,
    primaryEdit: EditorCompletionTextEdit,
    additionalEdits: List<EditorCompletionTextEdit>
): CompletionResolvedEdits? {
    val documentVersion = state.textBuffer.version
    val resolved = ArrayList<CompletionResolvedEdit>(additionalEdits.size + 1)
    resolved += resolveCompletionEdit(state, primaryEdit, primary = true) ?: return null
    additionalEdits.forEach { edit ->
        resolved += resolveCompletionEdit(state, edit, primary = false) ?: return null
    }
    if (state.textBuffer.version != documentVersion) return null
    if (hasOverlappingCompletionEdits(resolved)) return null
    return CompletionResolvedEdits(documentVersion = documentVersion, edits = resolved)
}

private fun hasOverlappingCompletionEdits(edits: List<CompletionResolvedEdit>): Boolean {
    for (leftIndex in edits.indices) {
        val left = edits[leftIndex]
        for (rightIndex in leftIndex + 1 until edits.size) {
            val right = edits[rightIndex]
            val leftIsInsertion = left.startOffset == left.endOffset
            val rightIsInsertion = right.startOffset == right.endOffset
            val overlaps = when {
                leftIsInsertion && rightIsInsertion -> left.startOffset == right.startOffset
                leftIsInsertion -> left.startOffset in right.startOffset until right.endOffset
                rightIsInsertion -> right.startOffset in left.startOffset until left.endOffset
                else -> maxOf(left.startOffset, right.startOffset) < minOf(left.endOffset, right.endOffset)
            }
            if (overlaps) return true
        }
    }
    return false
}

private fun resolveCompletionEdit(
    state: EditorState,
    edit: EditorCompletionTextEdit,
    primary: Boolean
): CompletionResolvedEdit? {
    val lineCount = state.textBuffer.lineCount
    if (lineCount <= 0) return null
    if (edit.startLine !in 0 until lineCount || edit.endLine !in 0 until lineCount) return null
    if (edit.startColumn < 0 || edit.endColumn < 0) return null
    if (
        edit.endLine < edit.startLine ||
        (edit.endLine == edit.startLine && edit.endColumn < edit.startColumn)
    ) {
        return null
    }

    val startLineLength = state.textBuffer.getLine(edit.startLine).length
    val endLineLength = state.textBuffer.getLine(edit.endLine).length
    if (edit.startColumn > startLineLength || edit.endColumn > endLineLength) return null

    val startOffset = state.textBuffer.positionToOffset(edit.startLine, edit.startColumn)
    val endOffset = state.textBuffer.positionToOffset(edit.endLine, edit.endColumn)
    return CompletionResolvedEdit(
        startOffset = startOffset,
        endOffset = endOffset,
        newText = edit.newText,
        primary = primary
    )
}

private fun synthesizePrimaryCompletionEdit(
    state: EditorState,
    insertText: String
): EditorCompletionTextEdit {
    val endOffset = state.cursorOffset.coerceIn(0, state.textBuffer.length)
    val endPos = state.textBuffer.offsetToPosition(endOffset)
    val lineText = state.textBuffer.getLine(endPos.line)
    val startColumn = TextScanKernel.findWordPrefixStart(lineText, endPos.column)
    return EditorCompletionTextEdit(
        startLine = endPos.line,
        startColumn = startColumn,
        endLine = endPos.line,
        endColumn = endPos.column,
        newText = insertText
    )
}

internal fun editorReplaceAll(
    state: EditorState,
    findText: String,
    replaceText: String,
    caseSensitive: Boolean,
    useRegex: Boolean
): Int {
    if (findText.isEmpty()) return 0

    val original = state.textBuffer.substring(0, state.textBuffer.length)
    val replacedResult = replaceTextByOptions(
        original = original,
        findText = findText,
        replaceText = replaceText,
        caseSensitive = caseSensitive,
        useRegex = useRegex
    ) ?: return 0
    val (replaced, count) = replacedResult
    if (replaced == original) return 0

    state.cancelSnippet()

    state.textBuffer.editTransaction(
        cursorBefore = state.cursorOffset,
        cursorAfter = { state.cursorOffset }
    ) {
        state.textBuffer.replace(0, state.textBuffer.length, replaced)
        state.moveCursorTo(0)
    }
    state.emitTextChanged(reason = "replaceAll")
    state.scrollToLine(0)
    return count
}

internal fun editorToggleLineComment(
    state: EditorState,
    commentToken: String
): Boolean {
    if (commentToken.isBlank()) return false
    state.cancelSnippet()

    val range = state.selectionRange
    val hasSelection = range != null && !range.isEmpty
    val curPos = state.cursorPosition
    val lineCount = state.textBuffer.lineCount
    if (lineCount <= 0) return false

    val startPos = if (hasSelection) {
        state.textBuffer.offsetToPosition(range!!.start)
    } else {
        curPos
    }
    val endPos = if (hasSelection) {
        state.textBuffer.offsetToPosition(range!!.end)
    } else {
        curPos
    }
    val startLine = startPos.line.coerceIn(0, lineCount - 1)
    var endLine = endPos.line.coerceIn(startLine, lineCount - 1)
    if (hasSelection && endPos.column == 0 && endLine > startLine) {
        endLine = (endLine - 1).coerceAtLeast(startLine)
    }

    val lineInfos = (startLine..endLine).map { lineIndex ->
        LineCommentTargetLine(
            lineStartOffset = state.textBuffer.getLineStart(lineIndex),
            text = state.textBuffer.getLine(lineIndex)
        )
    }
    if (lineInfos.isEmpty()) return false

    val shouldUncomment = lineInfos
        .asSequence()
        .map { it.text }
        .filter { it.isNotBlank() }
        .all { line ->
            val indent = TextScanKernel
                .scanLineWhitespace(line, state.config.tabSize)
                .leadingWhitespaceEnd
            line.substring(indent).startsWith(commentToken)
        }

    val edits = mutableListOf<LineCommentOffsetEdit>()
    val updatedLines = lineInfos.map { lineInfo ->
        val line = lineInfo.text
        if (line.isBlank()) {
            return@map line
        }

        val indent = TextScanKernel
            .scanLineWhitespace(line, state.config.tabSize)
            .leadingWhitespaceEnd
        val rest = line.substring(indent)
        when {
            shouldUncomment && rest.startsWith(commentToken) -> {
                val afterToken = rest.drop(commentToken.length)
                val optionalSpaceLength = if (afterToken.startsWith(" ")) 1 else 0
                val removeLength = commentToken.length + optionalSpaceLength
                edits += LineCommentOffsetEdit(
                    offset = lineInfo.lineStartOffset + indent,
                    oldLength = removeLength,
                    newLength = 0
                )
                line.substring(0, indent) + rest.drop(removeLength)
            }

            !shouldUncomment -> {
                val prefix = "$commentToken "
                edits += LineCommentOffsetEdit(
                    offset = lineInfo.lineStartOffset + indent,
                    oldLength = 0,
                    newLength = prefix.length
                )
                line.substring(0, indent) + prefix + rest
            }

            else -> line
        }
    }

    val segmentStartOffset = state.textBuffer.getLineStart(startLine)
    val segmentEndOffset = state.textBuffer.getLineEnd(endLine)
        .coerceAtLeast(segmentStartOffset)
        .coerceIn(0, state.textBuffer.length)
    val originalSegment = state.textBuffer.substring(segmentStartOffset, segmentEndOffset)
    val updatedSegment = updatedLines.joinToString("\n")
    if (updatedSegment == originalSegment || edits.isEmpty()) return false

    val selectionBeforeEdit = state.selectionRange
    val cursorBeforeEdit = state.cursorOffset

    state.textBuffer.editTransaction(
        cursorBefore = cursorBeforeEdit,
        cursorAfter = { state.cursorOffset }
    ) {
        state.textBuffer.replace(segmentStartOffset, segmentEndOffset, updatedSegment)
        state.emitTextChanged(reason = "toggleLineComment")

        fun adjustOffset(offset: Int): Int {
            return adjustOffsetAfterLineCommentEdits(
                offset = offset,
                edits = edits,
                textLength = state.textBuffer.length
            )
        }

        if (selectionBeforeEdit != null && !selectionBeforeEdit.isEmpty) {
            val updatedSelection = OffsetRange(
                anchor = adjustOffset(selectionBeforeEdit.anchor),
                caret = adjustOffset(selectionBeforeEdit.caret)
            )
            state.selectionRange = updatedSelection
            state.moveCursorTo(updatedSelection.caret, clearSelection = false)
            if (selectionBeforeEdit != state.selectionRange) {
                state.emitEvent(EditorEvent.SelectionChanged(state.selectionRange))
            }
        } else {
            state.moveCursorTo(adjustOffset(cursorBeforeEdit))
        }
    }
    return true
}

private data class LineCommentTargetLine(
    val lineStartOffset: Int,
    val text: String
)

private data class LineCommentOffsetEdit(
    val offset: Int,
    val oldLength: Int,
    val newLength: Int
) {
    val delta: Int get() = newLength - oldLength
}

private fun adjustOffsetAfterLineCommentEdits(
    offset: Int,
    edits: List<LineCommentOffsetEdit>,
    textLength: Int
): Int {
    var delta = 0
    for (edit in edits.sortedBy { it.offset }) {
        if (edit.oldLength == 0) {
            if (offset >= edit.offset) {
                delta += edit.newLength
            }
            continue
        }

        if (offset < edit.offset) {
            break
        }

        val editEnd = edit.offset + edit.oldLength
        if (offset <= editEnd) {
            return (edit.offset + delta).coerceIn(0, textLength)
        }
        delta += edit.delta
    }
    return (offset + delta).coerceIn(0, textLength)
}

/**
 * 对选中行做"缩进/反缩进"。
 */
internal fun editorIndentOrOutdentSelectionByTab(
    state: EditorState,
    outdent: Boolean
): Boolean {
    val lineCount = state.textBuffer.lineCount
    if (lineCount <= 0) return false

    val range = state.selectionRange
    val hasSelection = range != null && !range.isEmpty
    val curPos = state.cursorPosition
    val startPos = if (hasSelection) state.textBuffer.offsetToPosition(range!!.start) else curPos
    val endPos = if (hasSelection) state.textBuffer.offsetToPosition(range!!.end) else curPos

    var startLine = startPos.line.coerceIn(0, lineCount - 1)
    var endLine = endPos.line.coerceIn(startLine, lineCount - 1)

    if (hasSelection && endPos.column == 0 && endLine > startLine) {
        endLine = (endLine - 1).coerceAtLeast(startLine)
    }

    val tabSize = state.config.tabSize.coerceAtLeast(1)
    val indentUnit = if (state.config.insertSpacesForTabs) " ".repeat(tabSize) else "\t"
    val segmentStartOffset = state.textBuffer.getLineStart(startLine)
    val segmentEndOffset = state.textBuffer.getLineEnd(endLine)
        .coerceAtLeast(segmentStartOffset)
        .coerceIn(0, state.textBuffer.length)

    val original = state.textBuffer.substring(segmentStartOffset, segmentEndOffset)
    val lines = original.split('\n', limit = -1)
    val perLineColumnDelta = IntArray(lines.size)

    val updatedLines = lines.mapIndexed { index, line ->
        if (!outdent) {
            perLineColumnDelta[index] = indentUnit.length
            indentUnit + line
        } else {
            val removeCount = TextScanKernel.scanLineWhitespace(line, tabSize).outdentRemoveCount
            perLineColumnDelta[index] = -removeCount
            if (removeCount <= 0) {
                line
            } else {
                line.drop(removeCount)
            }
        }
    }
    val updated = updatedLines.joinToString("\n")
    if (updated == original) return false

    val oldSelection = state.selectionRange
    state.textBuffer.editTransaction(
        cursorBefore = state.cursorOffset,
        cursorAfter = { state.cursorOffset }
    ) {
        state.textBuffer.replace(segmentStartOffset, segmentEndOffset, updated)
        state.emitTextChanged(reason = if (outdent) "outdentLines" else "indentLines")

        fun adjustPosition(pos: Position): Position {
            val line = pos.line
            if (line !in startLine..endLine) return pos
            val delta = perLineColumnDelta.getOrElse(line - startLine) { 0 }
            val newColumn = (pos.column + delta).coerceAtLeast(0)
            val newLineText = state.textBuffer.getLine(line)
            return Position(line, newColumn.coerceIn(0, newLineText.length))
        }

        val newCursorPos = adjustPosition(curPos)
        state.moveCursorTo(
            state.textBuffer.positionToOffset(newCursorPos.line, newCursorPos.column),
            clearSelection = false
        )

        if (hasSelection) {
            val normalizedStart = adjustPosition(startPos)
            val normalizedEnd = adjustPosition(endPos)
            state.selectionRange = OffsetRange(
                state.textBuffer.positionToOffset(normalizedStart.line, normalizedStart.column),
                state.textBuffer.positionToOffset(normalizedEnd.line, normalizedEnd.column)
            )
        }

        if (oldSelection != state.selectionRange) {
            state.emitEvent(EditorEvent.SelectionChanged(state.selectionRange))
        }
    }

    return true
}

private fun deleteSelectionIfPresent(state: EditorState): Boolean {
    val range = state.selectionRange ?: return false
    if (range.isEmpty) {
        state.selectionRange = null
        return false
    }
    val start = range.start.coerceIn(0, state.textBuffer.length)
    val end = range.end.coerceIn(start, state.textBuffer.length)
    if (start < end) {
        state.textBuffer.delete(start, end)
        state.moveCursorTo(start)
        state.selectionRange = null
        return true
    }
    state.selectionRange = null
    return false
}

private fun replaceTextByOptions(
    original: String,
    findText: String,
    replaceText: String,
    caseSensitive: Boolean,
    useRegex: Boolean
): Pair<String, Int>? {
    if (useRegex) {
        val regex = runCatching {
            if (caseSensitive) Regex(findText) else Regex(findText, RegexOption.IGNORE_CASE)
        }.getOrNull() ?: return null

        val count = regex.findAll(original).count()
        if (count <= 0) return null
        return regex.replace(original, replaceText) to count
    }

    val count = countOccurrences(original, findText, ignoreCase = !caseSensitive)
    if (count <= 0) return null
    val replaced = original.replace(findText, replaceText, ignoreCase = !caseSensitive)
    return replaced to count
}

private fun countOccurrences(text: String, target: String, ignoreCase: Boolean): Int {
    if (target.isEmpty()) return 0
    var count = 0
    var start = 0
    while (true) {
        val index = text.indexOf(target, start, ignoreCase)
        if (index < 0) break
        count++
        start = index + target.length
    }
    return count
}
