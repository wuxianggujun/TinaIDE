package com.wuxianggujun.tinaide.core.editorview

import com.wuxianggujun.tinaide.core.textengine.TextBuffer

interface EditorStateSnapshot {
    val textBuffer: TextBuffer
    var cursorOffset: Int
    var selectionRange: OffsetRange?

    fun moveCursorTo(offset: Int, clearSelection: Boolean = true)
    fun selectRange(startOffset: Int, endOffset: Int)
}

interface EditorEditOperations {
    fun insert(text: String)
    fun backspace()
    fun deleteForward()
    fun replaceSelection(replacement: String): Boolean
    fun replaceRange(startOffset: Int, endOffset: Int, replacement: String): Boolean
    fun undo(): Boolean
    fun redo(): Boolean
}
