package com.wuxianggujun.tinaide.ui.runtime

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.wuxianggujun.tinaide.core.editorview.EditorColorScheme
import com.wuxianggujun.tinaide.core.editorview.EditorConfig
import com.wuxianggujun.tinaide.core.editorview.EditorGutterMode
import com.wuxianggujun.tinaide.core.editorview.EditorState
import com.wuxianggujun.tinaide.core.editorview.GutterDecoration
import com.wuxianggujun.tinaide.core.editorview.GutterLineLabel
import com.wuxianggujun.tinaide.core.editorview.OffsetRange
import com.wuxianggujun.tinaide.core.textengine.DefaultEditHistory
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer

internal data class GraphicalRuntimeLogEntry(val level: Char, val message: String)

internal const val GRAPHICAL_LOG_MAX_LINES = 500
internal const val GRAPHICAL_LOG_MAX_LINE_CHARS = 8_192
internal const val GRAPHICAL_LOG_MAX_CHARACTERS = 512 * 1_024

/** A single owner-thread document; append/trim never replace the editor or rebuild its buffer. */
internal class GraphicalRuntimeLogDocument : AutoCloseable {
    private val buffer = RopeTextBuffer(history = DefaultEditHistory(maxHistorySize = 0, maxHistoryCharacters = 0))
    val state = EditorState(buffer, config = EditorConfig(
        showLineNumbers = true,
        pinLineNumber = true,
        fontSizeSp = 11f,
        wordWrap = false,
        codeFolding = false,
        rainbowBrackets = false,
        bracketPairGuides = false,
        gutterMode = EditorGutterMode.LABELS,
    )).apply {
        colorScheme = EditorColorScheme.builtinDark().let {
            it.copy(currentLineBackground = Color.Transparent, lineNumberForegroundActive = it.lineNumberForeground)
        }
    }
    var lineCount by mutableIntStateOf(0)
        private set
    var revision by mutableLongStateOf(0L)
        private set
    var followingTail by mutableStateOf(true)
        private set
    private var closed = false

    fun append(entries: List<GraphicalRuntimeLogEntry>) {
        check(!closed) { "Graphical log document is closed" }
        if (entries.isEmpty()) return
        val previousSelection = state.selectionRange?.takeUnless { it.isEmpty }
        val wasFollowingTail = previousSelection == null && isAtEnd()
        val previousScroll = state.scrollOffsetPx
        val hasExistingLines = lineCount > 0
        var nextLine = if (hasExistingLines) buffer.lineCount else 0
        val appendedLabels = ArrayDeque<Pair<Int, GutterDecoration>>()
        val addition = entries.takeLast(GRAPHICAL_LOG_MAX_LINES).joinToString("\n") { entry ->
            val message = boundedMessage(entry.message)
            val decoration = GutterDecoration(label = GutterLineLabel(entry.level, levelColor(entry.level)))
            val endLine = nextLine + message.count { it == '\n' } + 1
            // Even one newline-heavy message must not allocate an unbounded metadata list.
            if (endLine - nextLine >= GRAPHICAL_LOG_MAX_LINES) appendedLabels.clear()
            for (line in maxOf(nextLine, endLine - GRAPHICAL_LOG_MAX_LINES) until endLine) {
                if (appendedLabels.size == GRAPHICAL_LOG_MAX_LINES) appendedLabels.removeFirst()
                appendedLabels.addLast(line to decoration)
            }
            nextLine = endLine
            message
        }
        buffer.insert(buffer.length, if (hasExistingLines) "\n$addition" else addition)

        var removedLines = (buffer.lineCount - GRAPHICAL_LOG_MAX_LINES).coerceAtLeast(0)
        if (buffer.length > GRAPHICAL_LOG_MAX_CHARACTERS) {
            val cutoff = buffer.length - GRAPHICAL_LOG_MAX_CHARACTERS
            val cutoffLine = buffer.offsetToPosition(cutoff).line
            val firstCompleteLine = cutoffLine + if (buffer.getLineStart(cutoffLine) < cutoff) 1 else 0
            removedLines = maxOf(removedLines, firstCompleteLine)
        }
        val removedHeight = if (removedLines > 0) state.lineTopInViewport(removedLines) + previousScroll else 0f
        val removedCharacters = if (removedLines > 0) buffer.getLineStart(removedLines) else 0
        if (removedCharacters > 0) buffer.delete(0, removedCharacters)
        if (removedLines > 0) {
            val previousLabels = state.gutterDecorations.toMap()
            state.gutterDecorations.clear()
            state.lineForegroundColors.clear()
            previousLabels.forEach { (line, decoration) ->
                if (line >= removedLines) setLineDecoration(line - removedLines, decoration)
            }
        }
        appendedLabels.forEach { (line, decoration) ->
            if (line >= removedLines) setLineDecoration(line - removedLines, decoration)
        }
        // External buffer edits normalize offsets but cannot know that this owner removed a prefix.
        // Keep the surviving selected text stable; discard a selection that has fully expired.
        if (previousSelection != null) {
            state.selectionRange = if (previousSelection.end <= removedCharacters) null else OffsetRange(
                (previousSelection.anchor - removedCharacters).coerceAtLeast(0),
                (previousSelection.caret - removedCharacters).coerceAtLeast(0),
            )
        }
        lineCount = buffer.lineCount
        revision++
        followingTail = wasFollowingTail
        if (followingTail) scrollToEnd()
        else state.scrollBy(previousScroll - removedHeight - state.scrollOffsetPx)
    }

    fun clear() {
        check(!closed) { "Graphical log document is closed" }
        if (buffer.length > 0) buffer.delete(0, buffer.length)
        state.gutterDecorations.clear()
        state.lineForegroundColors.clear()
        state.clearSelection()
        lineCount = 0
        revision++
        followingTail = true
        state.scrollBy(-state.scrollOffsetPx)
        state.scrollByX(-state.scrollOffsetXPx)
    }

    fun setWordWrap(enabled: Boolean) {
        if (state.config.wordWrap == enabled) return
        followingTail = state.selectionRange?.isEmpty != false && isAtEnd()
        state.config = state.config.copy(wordWrap = enabled)
        if (followingTail) scrollToEnd()
    }

    fun scrollToEnd() {
        if (state.selectionRange?.isEmpty == false) return
        state.scrollBy(state.maxVerticalScrollOffsetPx() - state.scrollOffsetPx)
    }

    fun onUserScroll() { followingTail = state.selectionRange?.isEmpty != false && isAtEnd() }

    private fun setLineDecoration(line: Int, decoration: GutterDecoration) {
        state.gutterDecorations[line] = decoration
        decoration.label?.let { state.lineForegroundColors[line] = it.color }
    }

    private fun isAtEnd(): Boolean = lineCount == 0 ||
        state.maxVerticalScrollOffsetPx() - state.scrollOffsetPx <= state.lineHeightPx

    private fun levelColor(level: Char): Color = with(state.colorScheme) {
        when (level) {
            'E', 'F' -> diagnosticError
            'W' -> diagnosticWarning
            'I' -> diagnosticInfo
            'D', 'V' -> diagnosticHint
            else -> lineNumberForeground
        }
    }

    private fun boundedMessage(message: String): String {
        val normalized = message.replace("\r\n", "\n").replace('\r', '\n')
        var end = minOf(normalized.length, GRAPHICAL_LOG_MAX_LINE_CHARS)
        if (end < normalized.length && end > 0 && normalized[end - 1].isHighSurrogate() && normalized[end].isLowSurrogate()) end--
        return normalized.substring(0, end)
    }

    override fun close() {
        if (closed) return
        closed = true
        state.close()
        buffer.close()
    }
}
