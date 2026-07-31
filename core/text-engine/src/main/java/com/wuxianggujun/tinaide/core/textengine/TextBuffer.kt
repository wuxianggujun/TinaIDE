package com.wuxianggujun.tinaide.core.textengine

import java.io.File
import java.nio.charset.Charset
import kotlinx.coroutines.flow.StateFlow

interface TextBuffer {
    val length: Int
    val lineCount: Int
    val version: Long

    /**
     * 版本号 StateFlow。每次文本变化（insert / delete / replace / undo / redo / load）都会 emit 最新 version。
     *
     * 作用：让订阅方（LSP semantic tokens、反向搜索等）不需要自己维护 callbackFlow + addChangeListener
     * 的注册/注销，避免上层 LaunchedEffect 重启时的 listener 注册风暴。
     */
    val versionFlow: StateFlow<Long>

    fun insert(offset: Int, text: String, historyCursor: TextEditCursorSnapshot? = null)
    fun delete(start: Int, end: Int, historyCursor: TextEditCursorSnapshot? = null)
    fun replace(
        start: Int,
        end: Int,
        text: String,
        historyCursor: TextEditCursorSnapshot? = null
    )

    fun beginCompoundEdit(
        cursorBefore: Int? = null,
        selectionBefore: TextSelectionSnapshot? = null
    ): CompoundEditToken

    fun endCompoundEdit(
        token: CompoundEditToken,
        cursorAfter: Int? = null,
        selectionAfter: TextSelectionSnapshot? = null
    )

    fun isCompoundEditActive(token: CompoundEditToken): Boolean

    /**
     * 将多个底层编辑记录为一次可撤销的用户操作。
     *
     * [cursorAfter] 在 [block] 成功执行后求值，允许调用方根据最终文档状态记录光标。
     */
    fun <T> editTransaction(
        cursorBefore: Int? = null,
        cursorAfter: (() -> Int?)? = null,
        selectionBefore: TextSelectionSnapshot? = null,
        selectionAfter: (() -> TextSelectionSnapshot?)? = null,
        block: TextBuffer.() -> T
    ): T

    fun substring(start: Int, end: Int): String
    fun charAt(offset: Int): Char?
    fun getLine(line: Int): String
    fun getLineStart(line: Int): Int
    fun getLineEnd(line: Int): Int
    fun offsetToLine(offset: Int): Int
    fun positionToOffset(line: Int, column: Int): Int
    fun offsetToPosition(offset: Int): Position

    fun addChangeListener(listener: TextChangeListener)
    fun removeChangeListener(listener: TextChangeListener)

    fun canUndo(): Boolean
    fun canRedo(): Boolean
    fun undo(): UndoRedoResult?
    fun redo(): UndoRedoResult?

    suspend fun loadFromFile(file: File, charset: Charset = Charsets.UTF_8): Result<Unit>
    suspend fun saveToFile(file: File, charset: Charset = Charsets.UTF_8): Result<Unit>
}

internal fun EditHistory.finishCompoundEdit(
    token: CompoundEditToken,
    completed: Boolean,
    cursorAfter: (() -> Int?)?,
    selectionAfter: (() -> TextSelectionSnapshot?)? = null
) {
    var resolvedCursorAfter: Int? = null
    var resolvedSelectionAfter: TextSelectionSnapshot? = null
    var resolutionFailure: Throwable? = null
    if (completed && cursorAfter != null) {
        try {
            resolvedCursorAfter = cursorAfter()
        } catch (throwable: Throwable) {
            resolutionFailure = throwable
        }
    }
    if (completed && selectionAfter != null) {
        try {
            resolvedSelectionAfter = selectionAfter()
        } catch (throwable: Throwable) {
            val previousFailure = resolutionFailure
            if (previousFailure == null) {
                resolutionFailure = throwable
            } else {
                previousFailure.addSuppressed(throwable)
            }
        }
    }

    try {
        endCompoundEdit(token, resolvedCursorAfter, resolvedSelectionAfter)
    } catch (endFailure: Throwable) {
        resolutionFailure?.let(endFailure::addSuppressed)
        throw endFailure
    }
    resolutionFailure?.let { throw it }
}
