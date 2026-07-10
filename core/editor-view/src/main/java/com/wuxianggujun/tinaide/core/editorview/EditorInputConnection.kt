package com.wuxianggujun.tinaide.core.editorview

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.TextUtils
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.EditorInfo.IME_ACTION_DONE
import android.view.inputmethod.EditorInfo.IME_ACTION_GO
import android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
import android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
import android.view.inputmethod.EditorInfo.IME_ACTION_SEND
import android.view.inputmethod.EditorInfo.IME_ACTION_UNSPECIFIED
import android.view.inputmethod.EditorInfo.IME_NULL
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import com.wuxianggujun.tinaide.core.config.Prefs
import kotlin.math.abs
import timber.log.Timber

internal class EditorInputHostView(context: Context) : View(context) {

    var inputConnectionFactory: ((EditorInfo) -> InputConnection)? = null
    var keyEventHandler: ((KeyEvent) -> Boolean)? = null
    var onWindowFocusChangedCallback: ((Boolean) -> Unit)? = null
    var onDetachedFromWindowCallback: (() -> Unit)? = null

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? = inputConnectionFactory?.invoke(outAttrs)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (keyEventHandler?.invoke(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        onWindowFocusChangedCallback?.invoke(hasWindowFocus)
    }

    override fun onDetachedFromWindow() {
        onDetachedFromWindowCallback?.invoke()
        super.onDetachedFromWindow()
    }
}

internal class EditorInputConnection(
    private val targetView: View,
    private val state: EditorState,
    private val onInsertedText: (String) -> Unit,
    private val onNonInsertEdit: () -> Unit
) : BaseInputConnection(targetView, true) {
    private companion object {
        private const val IME_DIAG_TAG = "EditorImeDiag"
        private const val AOSP_META_SELECTING = 0x800

        // getExtractedText 默认窗口 4KB：覆盖大部分中文/emoji 输入法的候选词与重组上下文，
        // 远小于"整份文档"但足够让 IME 正常工作。
        private const val DEFAULT_EXTRACTED_WINDOW_CHARS = 4096
        private const val MIN_EXTRACTED_WINDOW_CHARS = 256
        private const val MAX_EXTRACTED_WINDOW_CHARS = 64 * 1024
    }

    private var composingRange: ComposingRange? = null
    private val clipboardManager: ClipboardManager? by lazy(LazyThreadSafetyMode.NONE) {
        targetView.context.getSystemService(ClipboardManager::class.java)
    }

    // IME 在重组 / 候选切换时会多次命中 getTextBeforeCursor / getTextAfterCursor，
    // 内容完全由 (version, start, end) 决定，可用单槽 cache 直接返回上次结果，
    // 避免每次都走 rope.substring（分配 String + lock.read）。
    private var beforeCacheVersion: Long = Long.MIN_VALUE
    private var beforeCacheStart: Int = -1
    private var beforeCacheEnd: Int = -1
    private var beforeCacheText: String? = null
    private var afterCacheVersion: Long = Long.MIN_VALUE
    private var afterCacheStart: Int = -1
    private var afterCacheEnd: Int = -1
    private var afterCacheText: String? = null
    private var pendingDeadKeyAccent: Int = 0

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence {
        val cursorOffset = selectionOffsets().first
        val length = n.coerceIn(0, surroundingContextChars())
        val start = (cursorOffset - length).coerceAtLeast(0)
        val version = state.textBuffer.version
        val cached = beforeCacheText
        if (cached != null &&
            beforeCacheVersion == version &&
            beforeCacheStart == start &&
            beforeCacheEnd == cursorOffset
        ) {
            return cached
        }
        val result = state.textBuffer.substring(start, cursorOffset)
        beforeCacheVersion = version
        beforeCacheStart = start
        beforeCacheEnd = cursorOffset
        beforeCacheText = result
        return result
    }

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence {
        val cursorOffset = selectionOffsets().second
        val length = n.coerceIn(0, surroundingContextChars())
        val end = (cursorOffset + length).coerceAtMost(state.textBuffer.length)
        val version = state.textBuffer.version
        val cached = afterCacheText
        if (cached != null &&
            afterCacheVersion == version &&
            afterCacheStart == cursorOffset &&
            afterCacheEnd == end
        ) {
            return cached
        }
        val result = state.textBuffer.substring(cursorOffset, end)
        afterCacheVersion = version
        afterCacheStart = cursorOffset
        afterCacheEnd = end
        afterCacheText = result
        return result
    }

    private var selectedCacheVersion: Long = Long.MIN_VALUE
    private var selectedCacheStart: Int = -1
    private var selectedCacheEnd: Int = -1
    private var selectedCacheText: String? = null

    override fun getSelectedText(flags: Int): CharSequence? {
        val (start, end) = selectionOffsets()
        if (start >= end) return null
        val version = state.textBuffer.version
        val cached = selectedCacheText
        if (cached != null &&
            selectedCacheVersion == version &&
            selectedCacheStart == start &&
            selectedCacheEnd == end
        ) {
            return cached
        }
        val result = state.textBuffer.substring(start, end)
        selectedCacheVersion = version
        selectedCacheStart = start
        selectedCacheEnd = end
        selectedCacheText = result
        return result
    }

    override fun getCursorCapsMode(reqModes: Int): Int {
        val before = getTextBeforeCursor(surroundingContextChars(), 0)
        return TextUtils.getCapsMode(before, before.length, reqModes)
    }

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText {
        val (selStart, selEnd) = imeSelectionOffsets()
        val documentLength = state.textBuffer.length

        // 以光标 / 选区为中心取一个窗口，不再把整份文档拷贝给 IME。
        // 10MB 源文件下每次 commit 都分配 10MB String 是 GC stall 的主要来源。
        val hintMaxChars = request?.hintMaxChars?.takeIf { it > 0 } ?: DEFAULT_EXTRACTED_WINDOW_CHARS
        val windowCap = hintMaxChars.coerceIn(MIN_EXTRACTED_WINDOW_CHARS, MAX_EXTRACTED_WINDOW_CHARS)

        val selectionLength = (selEnd - selStart).coerceAtLeast(0)
        val paddingEachSide = (windowCap - selectionLength).coerceAtLeast(0) / 2
        val windowStart = (selStart - paddingEachSide).coerceAtLeast(0)
        val windowEnd = minOf(documentLength, windowStart + windowCap).coerceAtLeast(windowStart)

        val extractedText = if (windowEnd > windowStart) {
            state.textBuffer.substring(windowStart, windowEnd)
        } else {
            ""
        }
        val windowLength = (windowEnd - windowStart).coerceAtLeast(0)
        return ExtractedText().apply {
            text = extractedText
            // partialStart/End 只用于增量更新；getExtractedText 返回的是完整的当前窗口。
            // selectionStart/End 按 Android 协议必须是相对 startOffset 的窗口内坐标。
            partialStartOffset = -1
            partialEndOffset = -1
            selectionStart = extractedTextSelectionOffset(selStart, windowStart, windowLength)
            selectionEnd = extractedTextSelectionOffset(selEnd, windowStart, windowLength)
            this.flags = 0
            startOffset = windowStart
        }
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val rawReplacement = text?.toString() ?: return true
        val replacement = EditorClipboardBridge.recoverPossiblyTruncatedImeCommitText(rawReplacement)
        if (replacement.length != rawReplacement.length) {
            logIme(
                "commitText recoveredTruncatedImeText rawChars=${rawReplacement.length} " +
                    "recoveredChars=${replacement.length}"
            )
        }
        replaceCurrentImeEditRange(
            replacement = replacement,
            newCursorPosition = newCursorPosition,
            keepComposing = false,
            insertedCallback = onInsertedText
        )
        return true
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val replacement = text?.toString() ?: ""
        replaceCurrentImeEditRange(
            replacement = replacement,
            newCursorPosition = newCursorPosition,
            keepComposing = true,
            insertedCallback = { onNonInsertEdit() }
        )
        return true
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        val documentLength = state.textBuffer.length
        val mapped = mapImeSelectionToDocument(
            start = start,
            end = end,
            documentLength = documentLength
        )
        composingRange = normalizeComposingRange(
            start = mapped.first,
            end = mapped.second,
            documentLength = documentLength
        )
        return true
    }

    override fun finishComposingText(): Boolean {
        composingRange = null
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        return deleteSelectionOrSurroundingText(reason = "deleteSurroundingText") {
            imeDeleteSurroundingCharRange(
                cursorOffset = cursorOffset(),
                beforeLength = beforeLength,
                afterLength = afterLength,
                documentLength = state.textBuffer.length
            )
        }
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        return deleteSelectionOrSurroundingText(reason = "deleteSurroundingTextInCodePoints") {
            imeDeleteSurroundingCodePointRange(
                textBuffer = state.textBuffer,
                cursorOffset = cursorOffset(),
                beforeLength = beforeLength,
                afterLength = afterLength
            )
        }
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        val documentLength = state.textBuffer.length
        val before = imeSelectionOffsets()
        val (mappedStart, mappedEnd) = mapImeSelectionToDocument(
            start = start,
            end = end,
            documentLength = documentLength
        )
        logIme(
            "setSelection request=($start,$end) mapped=($mappedStart,$mappedEnd) " +
                "before=(${before.first},${before.second}) beforeLen=${abs(before.second - before.first)} " +
                "docLen=$documentLength"
        )

        if (mappedStart == mappedEnd) {
            state.moveCursorTo(mappedEnd)
        } else {
            state.selectRange(
                startOffset = mappedStart,
                endOffset = mappedEnd
            )
        }
        clearComposingIfCollapsedOrOutside(mappedStart, mappedEnd)
        val applied = imeSelectionOffsets()
        logIme(
            "setSelection applied=(${applied.first},${applied.second}) " +
                "afterLen=${abs(applied.second - applied.first)}"
        )
        return true
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean = handleKeyEvent(event) || super.sendKeyEvent(event)

    internal fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (shouldDeferModifiedKeyEvent(event)) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                state.backspace()
                val cursor = cursorOffset()
                clearComposingIfCollapsedOrOutside(
                    selectionStart = cursor,
                    selectionEnd = cursor
                )
                onNonInsertEdit()
                true
            }

            KeyEvent.KEYCODE_FORWARD_DEL -> {
                state.deleteForward()
                val cursor = cursorOffset()
                clearComposingIfCollapsedOrOutside(
                    selectionStart = cursor,
                    selectionEnd = cursor
                )
                onNonInsertEdit()
                true
            }

            KeyEvent.KEYCODE_ENTER -> {
                replaceCurrentImeEditRange(
                    replacement = "\n",
                    newCursorPosition = 1,
                    keepComposing = false,
                    insertedCallback = onInsertedText
                )
                true
            }

            KeyEvent.KEYCODE_TAB -> {
                replaceCurrentImeEditRange(
                    replacement = "\t",
                    newCursorPosition = 1,
                    keepComposing = false,
                    insertedCallback = onInsertedText
                )
                true
            }

            // 输入法工具栏方向键：移动光标
            // BaseInputConnection 默认不处理方向键，必须在此显式实现，否则输入法方向键无效。
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                state.moveLeft(extendSelection = shouldExtendSelection(event))
                syncImeSelectionAfterMove()
                true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                state.moveRight(extendSelection = shouldExtendSelection(event))
                syncImeSelectionAfterMove()
                true
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                state.moveUp(extendSelection = shouldExtendSelection(event))
                syncImeSelectionAfterMove()
                true
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                state.moveDown(extendSelection = shouldExtendSelection(event))
                syncImeSelectionAfterMove()
                true
            }

            KeyEvent.KEYCODE_MOVE_HOME -> {
                val pos = state.cursorPosition
                moveCursorToWithImeSelection(
                    targetOffset = state.textBuffer.positionToOffset(pos.line, 0),
                    extendSelection = shouldExtendSelection(event)
                )
                syncImeSelectionAfterMove()
                true
            }

            KeyEvent.KEYCODE_MOVE_END -> {
                val pos = state.cursorPosition
                val lineLen = state.textBuffer.getLine(pos.line).length
                moveCursorToWithImeSelection(
                    targetOffset = state.textBuffer.positionToOffset(pos.line, lineLen),
                    extendSelection = shouldExtendSelection(event)
                )
                syncImeSelectionAfterMove()
                true
            }

            KeyEvent.KEYCODE_PAGE_UP -> {
                movePageByVisualLines(
                    direction = -1,
                    extendSelection = shouldExtendSelection(event)
                )
                syncImeSelectionAfterMove()
                true
            }

            KeyEvent.KEYCODE_PAGE_DOWN -> {
                movePageByVisualLines(
                    direction = 1,
                    extendSelection = shouldExtendSelection(event)
                )
                syncImeSelectionAfterMove()
                true
            }

            else -> {
                val printableText = printableTextFromKeyEvent(event) ?: return false
                commitText(printableText, 1)
                true
            }
        }
    }

    private fun shouldDeferModifiedKeyEvent(event: KeyEvent): Boolean {
        if (!event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_FORWARD_DEL,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_MOVE_HOME,
            KeyEvent.KEYCODE_MOVE_END,
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_PAGE_DOWN -> true
            else -> false
        }
    }

    private fun movePageByVisualLines(direction: Int, extendSelection: Boolean) {
        val visualLineCount = state.visualLineCount()
        if (visualLineCount <= 0) return
        val position = state.cursorPosition
        val currentVisualLine = state.visualLineForPosition(position.line, position.column)
        val pageLineCount = ((state.viewportHeightPx / state.lineHeightPx).toInt() - 1)
            .coerceAtLeast(1)
        val targetVisualLine = (currentVisualLine + direction * pageLineCount)
            .coerceIn(0, visualLineCount - 1)
        val targetLine = state.docLineForVisualLine(targetVisualLine)
        val targetLineText = state.textBuffer.getLine(targetLine)
        val targetColumn = position.column
            .coerceIn(
                state.visualLineStartColumn(targetVisualLine),
                state.visualLineEndColumn(targetVisualLine)
            )
            .coerceIn(0, targetLineText.length)
        moveCursorToWithImeSelection(
            targetOffset = state.textBuffer.positionToOffset(targetLine, targetColumn),
            extendSelection = extendSelection
        )
    }

    private fun printableTextFromKeyEvent(event: KeyEvent): String? {
        if (event.isMetaPressed) return null
        if (event.isCtrlPressed && !event.isAltPressed) return null
        if (event.isAltPressed && !event.isCtrlPressed) return null
        val unicodeChar = event.unicodeChar
        if (unicodeChar == 0) return null

        val codePoint = unicodeChar and KeyCharacterMap.COMBINING_ACCENT_MASK
        if (!isInsertableCodePoint(codePoint)) {
            pendingDeadKeyAccent = 0
            return null
        }

        if ((unicodeChar and KeyCharacterMap.COMBINING_ACCENT) != 0) {
            pendingDeadKeyAccent = codePoint
            return null
        }

        val pendingAccent = pendingDeadKeyAccent
        pendingDeadKeyAccent = 0
        val resolvedCodePoints = if (pendingAccent != 0) {
            val combinedCodePoint = KeyCharacterMap.getDeadChar(pendingAccent, codePoint)
            if (combinedCodePoint != 0) {
                intArrayOf(combinedCodePoint)
            } else {
                intArrayOf(pendingAccent, codePoint)
            }
        } else {
            intArrayOf(codePoint)
        }

        if (resolvedCodePoints.any { !isInsertableCodePoint(it) }) return null
        return buildString {
            resolvedCodePoints.forEach { appendCodePoint(it) }
        }
    }

    private fun isInsertableCodePoint(codePoint: Int): Boolean = Character.isValidCodePoint(codePoint) &&
        !Character.isISOControl(codePoint)

    /**
     * 方向键移动后通知 IME 更新当前选区位置，确保输入法内部的 anchor/caret 感知与编辑器一致。
     * 仅用于移动/扩展选区场景，不触发 onNonInsertEdit（避免关闭补全等副作用）。
     */
    private fun syncCursorToImeAfterMove(selStart: Int, selEnd: Int) {
        val imm = targetView.context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm?.updateSelection(targetView, selStart, selEnd, -1, -1)
    }

    override fun commitCompletion(text: CompletionInfo?): Boolean {
        val completionText = text?.text?.toString() ?: return false
        replaceCurrentImeEditRange(
            replacement = completionText,
            newCursorPosition = 1,
            keepComposing = false,
            insertedCallback = onInsertedText
        )
        return true
    }

    override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean {
        val correctionText = correctionInfo?.newText?.toString() ?: return false
        replaceCurrentImeEditRange(
            replacement = correctionText,
            newCursorPosition = 1,
            keepComposing = false,
            insertedCallback = onInsertedText
        )
        return true
    }

    override fun performEditorAction(editorAction: Int): Boolean {
        // 很多第三方输入法（搜狗、百度、讯飞等）按下回车时不发送 sendKeyEvent(KEYCODE_ENTER)，
        // 而是调用 performEditorAction。若此处返回 false，回车键在这些输入法上将完全无效。
        // IME_ACTION_UNSPECIFIED / IME_NULL 也需要处理，因为部分输入法用它们表示普通换行。
        return when (editorAction) {
            IME_ACTION_DONE,
            IME_ACTION_GO,
            IME_ACTION_SEND,
            IME_ACTION_NEXT,
            IME_ACTION_SEARCH,
            IME_ACTION_UNSPECIFIED,
            IME_NULL -> {
                replaceCurrentImeEditRange(
                    replacement = "\n",
                    newCursorPosition = 1,
                    keepComposing = false,
                    insertedCallback = onInsertedText
                )
                true
            }
            else -> false
        }
    }

    override fun performContextMenuAction(id: Int): Boolean {
        return when (id) {
            android.R.id.selectAll -> {
                state.selectAll()
                composingRange = null
                onNonInsertEdit()
                val (start, end) = selectionOffsets()
                logIme(
                    "contextMenu selectAll selection=($start,$end) " +
                        "selectionLen=${end - start} docLen=${state.textBuffer.length}"
                )
                true
            }

            android.R.id.copy -> copySelectedTextToClipboard()

            android.R.id.cut -> {
                val (start, end) = selectionOffsets()
                if (start >= end) {
                    false
                } else {
                    val copied = copySelectedTextToClipboard()
                    if (copied) {
                        state.replaceRange(startOffset = start, endOffset = end, replacement = "")
                        clearComposingIfOverlapped(start, end)
                        onNonInsertEdit()
                    }
                    copied
                }
            }

            android.R.id.paste,
            android.R.id.pasteAsPlainText -> {
                val systemPasteText = clipboardManager
                    ?.primaryClip
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)
                    ?.coerceToText(targetView.context)
                    ?.toString()
                    ?.takeIf { it.isNotEmpty() }
                    ?: return false
                val pasteText = EditorClipboardBridge
                    .recoverPossiblyTruncatedClipboardText(systemPasteText)
                    ?: return false
                val beforeSelection = selectionOffsets()
                val editRange = currentImeEditRange()
                replaceCurrentImeEditRange(
                    replacement = pasteText,
                    newCursorPosition = 1,
                    keepComposing = false,
                    insertedCallback = { _ -> onNonInsertEdit() }
                )
                val afterSelection = selectionOffsets()
                logIme(
                    "contextMenu paste pastedChars=${pasteText.length} " +
                        "systemChars=${systemPasteText.length} " +
                        "replaceRange=(${editRange.first},${editRange.second}) " +
                        "beforeSel=(${beforeSelection.first},${beforeSelection.second}) " +
                        "afterSel=(${afterSelection.first},${afterSelection.second})"
                )
                true
            }

            else -> super.performContextMenuAction(id)
        }
    }

    override fun performPrivateCommand(action: String?, data: Bundle?): Boolean = false

    private fun currentImeEditRange(): Pair<Int, Int> {
        val selection = selectionOffsets()
        return resolveEditRange(
            selectionStart = selection.first,
            selectionEnd = selection.second,
            composingRange = composingRange
        )
    }

    private fun replaceCurrentImeEditRange(
        replacement: String,
        newCursorPosition: Int,
        keepComposing: Boolean,
        insertedCallback: (String) -> Unit
    ) {
        val editRange = currentImeEditRange()
        applyReplacement(
            startOffset = editRange.first,
            endOffset = editRange.second,
            replacement = replacement,
            newCursorPosition = newCursorPosition,
            keepComposing = keepComposing,
            insertedCallback = insertedCallback
        )
    }

    private fun deleteSelectionOrSurroundingText(
        reason: String,
        surroundingRange: () -> ImeDeleteRange?
    ): Boolean {
        val selectedRange = state.selectionRange
            ?.takeUnless { it.isEmpty }
            ?.let { ImeDeleteRange(start = it.start, end = it.end) }
        val deleteRange = selectedRange ?: surroundingRange() ?: return true
        if (deleteRange.isEmpty) return true

        val changed = state.replaceRange(
            startOffset = deleteRange.start,
            endOffset = deleteRange.end,
            replacement = ""
        )
        if (changed) {
            clearComposingIfOverlapped(deleteRange.start, deleteRange.end)
            onNonInsertEdit()
            logIme("$reason deleteRange=(${deleteRange.start},${deleteRange.end})")
        }
        return true
    }

    private fun applyReplacement(
        startOffset: Int,
        endOffset: Int,
        replacement: String,
        newCursorPosition: Int,
        keepComposing: Boolean,
        insertedCallback: (String) -> Unit
    ) {
        val resolved = EditorSmartReplacement.resolve(
            state = state,
            startOffset = startOffset,
            replacement = replacement
        )
        val changed = state.replaceRange(
            startOffset = startOffset,
            endOffset = endOffset,
            replacement = resolved.replacement
        )
        composingRange = nextComposingRange(
            editStart = startOffset,
            replacementLength = resolved.replacement.length,
            keepComposing = keepComposing
        )

        if (resolved.cursorOffsetAfterInsert != null) {
            state.moveCursorTo(resolved.cursorOffsetAfterInsert)
        } else {
            val targetOffset = when {
                newCursorPosition > 0 -> startOffset + resolved.replacement.length + newCursorPosition - 1
                else -> startOffset + newCursorPosition
            }.coerceIn(0, state.textBuffer.length)
            state.moveCursorTo(targetOffset)
        }

        if (!changed) return
        if (resolved.replacement.isNotEmpty()) {
            insertedCallback(resolved.replacement)
        } else {
            onNonInsertEdit()
        }
    }

    private fun clearComposingIfOverlapped(editStart: Int, editEnd: Int) {
        val composing = composingRange ?: return
        if (editEnd <= composing.start || editStart >= composing.end) {
            return
        }
        composingRange = null
    }

    private fun clearComposingIfCollapsedOrOutside(selectionStart: Int, selectionEnd: Int) {
        val composing = composingRange ?: return
        val safeStart = minOf(selectionStart, selectionEnd)
        val safeEnd = maxOf(selectionStart, selectionEnd)
        if (safeStart == safeEnd && safeStart !in composing.start..composing.end) {
            composingRange = null
            return
        }
        if (safeStart < composing.start || safeEnd > composing.end) {
            composingRange = null
        }
    }

    private fun moveCursorToWithImeSelection(targetOffset: Int, extendSelection: Boolean) {
        val safeTarget = targetOffset.coerceIn(0, state.textBuffer.length)
        if (!extendSelection) {
            state.moveCursorTo(safeTarget)
            return
        }
        if (state.selectionRange == null) {
            state.startSelection(cursorOffset())
        }
        state.updateSelectionTo(safeTarget)
    }

    private fun syncImeSelectionAfterMove() {
        val selection = selectionOffsets()
        clearComposingIfCollapsedOrOutside(selection.first, selection.second)
        val imeSelection = imeSelectionOffsets()
        syncCursorToImeAfterMove(imeSelection.first, imeSelection.second)
    }

    /**
     * 对齐 AOSP ArrowKeyMovementMethod：SHIFT 或 META_SELECTING 都表示“扩展选区”。
     */
    private fun shouldExtendSelection(event: KeyEvent): Boolean {
        val metaState = KeyEvent.normalizeMetaState(event.metaState)
        return event.isShiftPressed || (metaState and AOSP_META_SELECTING) != 0
    }

    private fun selectionOffsets(): Pair<Int, Int> {
        val range = state.selectionRange
        if (range != null && !range.isEmpty) {
            return range.start to range.end
        }
        val cursor = cursorOffset()
        return cursor to cursor
    }

    private fun imeSelectionOffsets(): Pair<Int, Int> {
        val range = state.selectionRange
        if (range != null) {
            return range.anchor to range.caret
        }
        val cursor = cursorOffset()
        return cursor to cursor
    }

    private fun cursorOffset(): Int = state.cursorOffset.coerceIn(0, state.textBuffer.length)

    private fun surroundingContextChars(): Int = state.config.imeWindowChars.coerceIn(64, 4096)

    private fun copySelectedTextToClipboard(): Boolean {
        val (start, end) = selectionOffsets()
        if (start >= end) {
            logIme("contextMenu copy ignoredEmptySelection selection=($start,$end)")
            return false
        }
        val manager = clipboardManager ?: return false
        val selectedText = state.textBuffer.substring(start, end)
        manager.setPrimaryClip(
            ClipData.newPlainText("editor-selection", selectedText)
        )
        EditorClipboardBridge.rememberCopiedText(selectedText)
        logIme(
            "contextMenu copy selection=($start,$end) selectionLen=${end - start} " +
                "copiedChars=${selectedText.length}"
        )
        return true
    }

    private fun logIme(message: String) {
        if (!isImeDiagnosticsEnabled()) return
        Timber.tag(IME_DIAG_TAG).d(message)
    }

    private fun isImeDiagnosticsEnabled(): Boolean = runCatching { Prefs.devDiagnosticsEnabled }.getOrDefault(false)
}
