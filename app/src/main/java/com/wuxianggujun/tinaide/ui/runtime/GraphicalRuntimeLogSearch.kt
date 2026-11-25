package com.wuxianggujun.tinaide.ui.runtime

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wuxianggujun.tinaide.core.editorview.EditorState
import com.wuxianggujun.tinaide.core.editorview.OffsetRange
import com.wuxianggujun.tinaide.search.CodeSearchEngine
import com.wuxianggujun.tinaide.search.CodeSearchResult
import com.wuxianggujun.tinaide.search.SearchOptions
import com.wuxianggujun.tinaide.search.TextContentProvider

/** Literal search over the retained log buffer, reusing the editor search engine and selection. */
internal class GraphicalRuntimeLogSearch(private val editor: EditorState) {
    var active by mutableStateOf(false)
        private set
    var editingQuery by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
        private set
    var matchCount by mutableIntStateOf(0)
        private set
    var currentIndex by mutableIntStateOf(-1)
        private set
    var capped by mutableStateOf(false)
        private set
    private var matches = emptyList<OffsetRange>()
    private val engine = CodeSearchEngine(object : TextContentProvider {
        override fun getText() = editor.textBuffer.substring(0, editor.textBuffer.length)
        override fun getPosition(charIndex: Int): TextContentProvider.Position =
            editor.textBuffer.offsetToPosition(charIndex).let { TextContentProvider.Position(it.line, it.column) }
    }, maxResults = MAX_MATCHES + 1)

    fun open() {
        active = true
        editingQuery = true
        refresh()
        if (currentIndex < 0 && matches.isNotEmpty()) currentIndex = 0
        revealCurrent()
    }

    fun close() {
        active = false
        finishEditing()
        if (editor.selectionRange == matches.getOrNull(currentIndex)) editor.clearSelection()
        // Closed searches are not refreshed on append; only the query survives a later reopen.
        matches = emptyList()
        matchCount = 0
        currentIndex = -1
        capped = false
    }

    fun finishEditing() { editingQuery = false }

    fun updateQuery(value: String) {
        if (query == value) return
        query = value
        refresh()
        currentIndex = if (matches.isEmpty()) -1 else 0
        editor.clearSelection()
        revealCurrent()
    }

    fun refresh(removedCharacters: Int = 0) {
        if (!active) return
        val previousStart = matches.getOrNull(currentIndex)?.start?.minus(removedCharacters)
        val found = engine.search(query, SearchOptions()).filterIsInstance<CodeSearchResult>()
        capped = found.size > MAX_MATCHES
        matches = found.take(MAX_MATCHES).map { OffsetRange(it.range.startIndex, it.range.endIndex) }
        matchCount = matches.size
        currentIndex = matches.indexOfFirst { it.start == previousStart }
    }

    fun move(forward: Boolean) {
        if (matches.isEmpty()) return
        currentIndex = when {
            currentIndex < 0 -> if (forward) 0 else matches.lastIndex
            forward -> (currentIndex + 1) % matches.size
            else -> (currentIndex - 1 + matches.size) % matches.size
        }
        revealCurrent()
    }

    fun revealCurrent() {
        matches.getOrNull(currentIndex)?.let { editor.selectRange(it.start, it.end) }
    }

    private companion object { const val MAX_MATCHES = 2_000 }
}
