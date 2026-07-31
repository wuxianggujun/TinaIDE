package com.wuxianggujun.tinaide.core.textengine

sealed interface EditOperation {
    data class Insert(
        val offset: Int,
        val text: String,
        val cursorSnapshot: TextEditCursorSnapshot? = null
    ) : EditOperation

    data class Delete(
        val offset: Int,
        val text: String,
        val cursorSnapshot: TextEditCursorSnapshot? = null
    ) : EditOperation

    /**
     * 原子替换。undo 一次恢复原文、redo 一次重放替换——不再需要用户按两下 Ctrl+Z
     * 才能完全撤销一次 find-replace / 格式化 / snippet 替换。
     */
    data class Replace(
        val offset: Int,
        val oldText: String,
        val newText: String,
        val cursorSnapshot: TextEditCursorSnapshot? = null
    ) : EditOperation

    /**
     * 一次用户意图产生的多个底层编辑。
     *
     * 例如 LSP completion 的主编辑与 additionalTextEdits、snippet 镜像占位符同步修改，
     * 都必须作为一个整体撤销/重做。光标位置由事务边界记录，避免上层根据最后一个子编辑猜测。
     */
    data class Compound(
        val operations: List<EditOperation>,
        val cursorBefore: Int?,
        val cursorAfter: Int?,
        val selectionBefore: TextSelectionSnapshot? = null,
        val selectionAfter: TextSelectionSnapshot? = null
    ) : EditOperation {
        init {
            require(operations.isNotEmpty()) { "Compound edit must contain at least one operation" }
        }
    }
}

interface EditHistory {
    fun canUndo(): Boolean
    fun canRedo(): Boolean
    fun record(operation: EditOperation)
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
    fun undo(): EditOperation?
    fun redo(): EditOperation?
    fun clear()
}

data class CompoundEditToken internal constructor(
    internal val generation: Long,
    internal val id: Long
)

class DefaultEditHistory(
    private val maxHistorySize: Int = 1000,
    private val maxHistoryCharacters: Long = 8L * 1024L * 1024L,
    private val mergeWindowMs: Long = 300L,
    private val nowMs: () -> Long = System::currentTimeMillis
) : EditHistory {

    init {
        require(maxHistorySize >= 0) { "maxHistorySize must not be negative" }
        require(maxHistoryCharacters >= 0L) { "maxHistoryCharacters must not be negative" }
        require(mergeWindowMs >= 0L) { "mergeWindowMs must not be negative" }
    }

    private val undoStack = ArrayDeque<EditOperation>()
    private val redoStack = ArrayDeque<EditOperation>()
    private var storedCharacterCount: Long = 0L

    // 合并状态：仅当 record 之间间隔 <= mergeWindowMs 且位置相邻时，
    // 连续的 Insert/Delete 会被合并成一条；undo/redo/Replace/clear 均会打断。
    private var lastRecordTimeMs: Long = Long.MIN_VALUE
    private var mergeChainActive: Boolean = false

    private val activeCompoundTokens = ArrayDeque<CompoundEditToken>()
    private var compoundGeneration: Long = 0L
    private var nextCompoundTokenId: Long = 0L
    private val compoundOperations = mutableListOf<EditOperation>()
    private var compoundCursorBefore: Int? = null
    private var compoundSelectionBefore: TextSelectionSnapshot? = null
    private var compoundCharacterCount: Long = 0L
    private var compoundExceededBudget: Boolean = false

    override fun canUndo(): Boolean = !hasOpenCompoundScope() && undoStack.isNotEmpty()

    override fun canRedo(): Boolean = !hasOpenCompoundScope() && redoStack.isNotEmpty()

    override fun record(operation: EditOperation) {
        val now = nowMs()
        clearRedoStack()

        if (activeCompoundTokens.isNotEmpty()) {
            appendCompoundOperation(operation)
            mergeChainActive = false
            lastRecordTimeMs = now
            return
        }

        val elapsedSinceLastRecord = now - lastRecordTimeMs
        val merged = if (
            mergeChainActive &&
            now >= lastRecordTimeMs &&
            elapsedSinceLastRecord in 0L..mergeWindowMs
        ) {
            tryMergeWithTop(operation)
        } else {
            false
        }

        if (!merged) {
            pushUndo(operation)
        }

        // 只有普通 Insert/Delete 可以继续参与输入流合并。
        mergeChainActive = operation is EditOperation.Insert || operation is EditOperation.Delete
        lastRecordTimeMs = now
    }

    override fun beginCompoundEdit(
        cursorBefore: Int?,
        selectionBefore: TextSelectionSnapshot?
    ): CompoundEditToken {
        if (activeCompoundTokens.isEmpty()) {
            compoundOperations.clear()
            compoundCursorBefore = cursorBefore
            compoundSelectionBefore = selectionBefore
            compoundCharacterCount = 0L
            compoundExceededBudget = false
            mergeChainActive = false
            lastRecordTimeMs = Long.MIN_VALUE
        }
        val token = CompoundEditToken(
            generation = compoundGeneration,
            id = nextCompoundTokenId++
        )
        activeCompoundTokens.addLast(token)
        return token
    }

    override fun endCompoundEdit(
        token: CompoundEditToken,
        cursorAfter: Int?,
        selectionAfter: TextSelectionSnapshot?
    ) {
        if (token.generation != compoundGeneration) return
        check(activeCompoundTokens.lastOrNull() == token) {
            "Compound edit scopes must close in reverse order"
        }
        activeCompoundTokens.removeLast()
        if (activeCompoundTokens.isNotEmpty()) return

        val operations = compoundOperations.toList()
        val cursorBefore = compoundCursorBefore
        val selectionBefore = compoundSelectionBefore
        val exceededBudget = compoundExceededBudget
        compoundOperations.clear()
        compoundCursorBefore = null
        compoundSelectionBefore = null
        compoundCharacterCount = 0L
        compoundExceededBudget = false
        mergeChainActive = false
        lastRecordTimeMs = Long.MIN_VALUE

        if (exceededBudget) return
        if (operations.isEmpty()) return

        val operation = if (
            operations.size == 1 &&
            cursorBefore == null &&
            cursorAfter == null &&
            selectionBefore == null &&
            selectionAfter == null
        ) {
            operations.single()
        } else {
            EditOperation.Compound(
                operations = operations,
                cursorBefore = cursorBefore,
                cursorAfter = cursorAfter,
                selectionBefore = selectionBefore,
                selectionAfter = selectionAfter
            )
        }
        pushUndo(operation)
    }

    override fun isCompoundEditActive(token: CompoundEditToken): Boolean =
        token.generation == compoundGeneration && token in activeCompoundTokens

    private fun pushUndo(operation: EditOperation) {
        val characterCount = operation.retainedCharacterCount()
        if (maxHistorySize == 0 || characterCount > maxHistoryCharacters) {
            clearStacks()
            return
        }
        undoStack.addLast(operation)
        storedCharacterCount += characterCount
        while (undoStack.size > maxHistorySize || storedCharacterCount > maxHistoryCharacters) {
            storedCharacterCount -= undoStack.removeFirst().retainedCharacterCount()
        }
    }

    private fun appendCompoundOperation(operation: EditOperation) {
        if (compoundExceededBudget) return

        val previous = compoundOperations.lastOrNull()
        val merged = previous?.mergeSequentialReplacement(operation)
        if (merged != null) {
            compoundOperations.removeAt(compoundOperations.lastIndex)
            compoundCharacterCount -= previous.retainedCharacterCount()
            if (merged.oldText != merged.newText) {
                val mergedOperation = merged.toEditOperation()
                compoundOperations.add(mergedOperation)
                compoundCharacterCount += mergedOperation.retainedCharacterCount()
            }
        } else {
            compoundOperations.add(operation)
            compoundCharacterCount += operation.retainedCharacterCount()
        }

        if (compoundCharacterCount > maxHistoryCharacters) {
            compoundOperations.clear()
            compoundCharacterCount = 0L
            compoundExceededBudget = true
            clearStacks()
        }
    }

    private fun tryMergeWithTop(operation: EditOperation): Boolean {
        val previous = undoStack.lastOrNull() ?: return false
        when (operation) {
            is EditOperation.Insert -> {
                if (previous !is EditOperation.Insert) return false
                // 顺序输入：new.offset == prev.offset + prev.text.length。
                if (operation.offset != previous.offset + previous.text.length) return false
                if (containsMergeBoundary(previous.text) || containsMergeBoundary(operation.text)) return false
                undoStack.removeLast()
                storedCharacterCount -= previous.retainedCharacterCount()
                pushUndo(
                    EditOperation.Insert(
                        offset = previous.offset,
                        text = previous.text + operation.text,
                        cursorSnapshot = mergeCursorSnapshots(previous, operation)
                    )
                )
                return true
            }

            is EditOperation.Delete -> {
                if (previous !is EditOperation.Delete) return false
                if (containsMergeBoundary(previous.text) || containsMergeBoundary(operation.text)) return false
                // Backspace：new.offset + new.text.length == prev.offset。
                if (operation.offset + operation.text.length == previous.offset) {
                    undoStack.removeLast()
                    storedCharacterCount -= previous.retainedCharacterCount()
                    pushUndo(
                        EditOperation.Delete(
                            offset = operation.offset,
                            text = operation.text + previous.text,
                            cursorSnapshot = mergeCursorSnapshots(previous, operation)
                        )
                    )
                    return true
                }
                // Delete 键：new.offset == prev.offset。
                if (operation.offset == previous.offset) {
                    undoStack.removeLast()
                    storedCharacterCount -= previous.retainedCharacterCount()
                    pushUndo(
                        EditOperation.Delete(
                            offset = previous.offset,
                            text = previous.text + operation.text,
                            cursorSnapshot = mergeCursorSnapshots(previous, operation)
                        )
                    )
                    return true
                }
                return false
            }

            is EditOperation.Replace -> return false
            is EditOperation.Compound -> return false
        }
    }

    // 回车 / Tab 等视觉边界上停止合并，对齐 IntelliJ / Sora 的直觉：
    // 敲完一行后的 undo 应该回到上一行末，而不是一口气吞掉整段。
    private fun containsMergeBoundary(text: String): Boolean {
        for (i in text.indices) {
            val ch = text[i]
            if (ch == '\n' || ch == '\r' || ch == '\t') return true
        }
        return false
    }

    private fun mergeCursorSnapshots(
        previous: EditOperation,
        next: EditOperation
    ): TextEditCursorSnapshot? {
        val previousSnapshot = previous.cursorSnapshotOrNull()
        val nextSnapshot = next.cursorSnapshotOrNull()
        if (previousSnapshot == null && nextSnapshot == null) return null

        return TextEditCursorSnapshot(
            cursorBefore = previousSnapshot?.cursorBefore ?: previous.defaultCursorBefore(),
            cursorAfter = nextSnapshot?.cursorAfter ?: next.defaultCursorAfter()
        )
    }

    private fun EditOperation.cursorSnapshotOrNull(): TextEditCursorSnapshot? = when (this) {
        is EditOperation.Insert -> cursorSnapshot
        is EditOperation.Delete -> cursorSnapshot
        is EditOperation.Replace -> cursorSnapshot
        is EditOperation.Compound -> null
    }

    private fun EditOperation.defaultCursorBefore(): Int = when (this) {
        is EditOperation.Insert -> offset
        is EditOperation.Delete -> offset + text.length
        is EditOperation.Replace -> offset + oldText.length
        is EditOperation.Compound -> cursorBefore ?: operations.first().defaultCursorBefore()
    }

    private fun EditOperation.defaultCursorAfter(): Int = when (this) {
        is EditOperation.Insert -> offset + text.length
        is EditOperation.Delete -> offset
        is EditOperation.Replace -> offset + newText.length
        is EditOperation.Compound -> cursorAfter ?: operations.last().defaultCursorAfter()
    }

    override fun undo(): EditOperation? {
        check(!hasOpenCompoundScope()) { "Cannot undo during a compound edit" }
        val operation = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(operation)
        mergeChainActive = false
        return operation
    }

    override fun redo(): EditOperation? {
        check(!hasOpenCompoundScope()) { "Cannot redo during a compound edit" }
        val operation = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(operation)
        mergeChainActive = false
        return operation
    }

    override fun clear() {
        clearStacks()
        mergeChainActive = false
        lastRecordTimeMs = Long.MIN_VALUE
        compoundOperations.clear()
        compoundCursorBefore = null
        compoundSelectionBefore = null
        compoundCharacterCount = 0L
        compoundExceededBudget = false
        activeCompoundTokens.clear()
        compoundGeneration++
    }

    private fun clearRedoStack() {
        while (redoStack.isNotEmpty()) {
            storedCharacterCount -= redoStack.removeLast().retainedCharacterCount()
        }
    }

    private fun clearStacks() {
        undoStack.clear()
        redoStack.clear()
        storedCharacterCount = 0L
    }

    private fun hasOpenCompoundScope(): Boolean = activeCompoundTokens.isNotEmpty()

    private data class Replacement(
        val offset: Int,
        val oldText: String,
        val newText: String,
        val cursorSnapshot: TextEditCursorSnapshot?
    ) {
        fun toEditOperation(): EditOperation = when {
            oldText.isEmpty() -> EditOperation.Insert(offset, newText, cursorSnapshot)
            newText.isEmpty() -> EditOperation.Delete(offset, oldText, cursorSnapshot)
            else -> EditOperation.Replace(offset, oldText, newText, cursorSnapshot)
        }
    }

    private fun EditOperation.mergeSequentialReplacement(next: EditOperation): Replacement? {
        val previousReplacement = asReplacement() ?: return null
        val nextReplacement = next.asReplacement() ?: return null
        if (previousReplacement.offset != nextReplacement.offset) return null
        if (previousReplacement.newText != nextReplacement.oldText) return null
        return Replacement(
            offset = previousReplacement.offset,
            oldText = previousReplacement.oldText,
            newText = nextReplacement.newText,
            cursorSnapshot = mergeCursorSnapshots(this, next)
        )
    }

    private fun EditOperation.asReplacement(): Replacement? = when (this) {
        is EditOperation.Insert -> Replacement(offset, oldText = "", newText = text, cursorSnapshot)
        is EditOperation.Delete -> Replacement(offset, oldText = text, newText = "", cursorSnapshot)
        is EditOperation.Replace -> Replacement(offset, oldText, newText, cursorSnapshot)
        is EditOperation.Compound -> null
    }

    private fun EditOperation.retainedCharacterCount(): Long = when (this) {
        is EditOperation.Insert -> text.length.toLong()
        is EditOperation.Delete -> text.length.toLong()
        is EditOperation.Replace -> oldText.length.toLong() + newText.length.toLong()
        is EditOperation.Compound -> operations.sumOf { it.retainedCharacterCount() }
    }
}
