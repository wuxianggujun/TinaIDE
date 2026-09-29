package com.wuxianggujun.tinaide.core.editorview

import com.wuxianggujun.tinaide.core.textengine.TextBuffer
import com.wuxianggujun.tinaide.core.textengine.TextChange
import com.wuxianggujun.tinaide.core.textengine.TextScanKernel

/**
 * 维护文档每一行起始处的括号深度，避免重复从文档头部扫描。
 */
internal class BracketDepthCache {

    private var cachedVersion = -1L
    private var cachedLineDepths: IntArray = IntArray(0)
    private var cachedLineCount = 0
    private var dirtyFromLine = 0

    fun lineStartDepth(textBuffer: TextBuffer, line: Int): Int {
        val lineCount = textBuffer.lineCount.coerceAtLeast(0)
        if (lineCount <= 0) return 0

        val safeLine = line.coerceIn(0, lineCount - 1)
        ensureLineDepthCacheUpTo(textBuffer, safeLine)
        return cachedLineDepths.getOrElse(safeLine) { 0 }
    }

    fun depthAt(textBuffer: TextBuffer, line: Int, column: Int): Int {
        val lineCount = textBuffer.lineCount.coerceAtLeast(0)
        if (lineCount <= 0) return 0

        val safeLine = line.coerceIn(0, lineCount - 1)
        ensureLineDepthCacheUpTo(textBuffer, safeLine)
        if (column <= 0) {
            return cachedLineDepths[safeLine]
        }
        val lineText = textBuffer.getLine(safeLine)
        return TextScanKernel.advanceBracketDepth(cachedLineDepths[safeLine], lineText, column)
    }

    fun applyTextChange(change: TextChange, currentVersion: Long, currentLineCount: Int) {
        val safeLineCount = currentLineCount.coerceAtLeast(0)
        if (safeLineCount <= 0) {
            invalidate()
            return
        }
        if (cachedVersion < 0L || cachedLineDepths.isEmpty()) {
            cachedVersion = currentVersion
            cachedLineDepths = IntArray(safeLineCount)
            cachedLineCount = safeLineCount
            dirtyFromLine = 0
            return
        }

        val preservedCount = change.startLine.coerceIn(0, minOf(cachedLineCount, safeLineCount))
        val updatedDepths = IntArray(safeLineCount)
        if (preservedCount > 0) {
            System.arraycopy(cachedLineDepths, 0, updatedDepths, 0, preservedCount)
        }
        cachedVersion = currentVersion
        cachedLineDepths = updatedDepths
        cachedLineCount = safeLineCount
        dirtyFromLine = minOf(dirtyFromLine.coerceAtMost(preservedCount), preservedCount)
    }

    fun seedPrefixBoundaryDepths(
        currentVersion: Long,
        currentLineCount: Int,
        startLine: Int,
        targetLine: Int,
        boundaryDepths: IntArray
    ) {
        val safeLineCount = currentLineCount.coerceAtLeast(0)
        if (safeLineCount <= 0 || boundaryDepths.isEmpty()) return

        if (cachedVersion != currentVersion ||
            cachedLineCount != safeLineCount ||
            cachedLineDepths.size != safeLineCount
        ) {
            cachedVersion = currentVersion
            cachedLineDepths = IntArray(safeLineCount)
            cachedLineCount = safeLineCount
            dirtyFromLine = 0
        }

        val safeStartLine = startLine.coerceIn(0, safeLineCount - 1)
        val safeTargetLine = targetLine.coerceIn(safeStartLine, safeLineCount - 1)
        val coveredLineCount = minOf(
            safeTargetLine - safeStartLine + 1,
            boundaryDepths.size - 1,
            safeLineCount - safeStartLine
        )
        for (lineIndex in 0 until coveredLineCount) {
            cachedLineDepths[safeStartLine + lineIndex] = boundaryDepths[lineIndex]
        }

        val nextLine = safeStartLine + coveredLineCount
        val knownExclusiveEnd = if (nextLine < safeLineCount && boundaryDepths.size > coveredLineCount) {
            cachedLineDepths[nextLine] = boundaryDepths[coveredLineCount]
            nextLine + 1
        } else {
            nextLine
        }
        dirtyFromLine = maxOf(dirtyFromLine, knownExclusiveEnd.coerceAtMost(safeLineCount))
    }

    fun invalidate() {
        cachedVersion = -1L
        cachedLineDepths = IntArray(0)
        cachedLineCount = 0
        dirtyFromLine = 0
    }

    private fun ensureLineDepthCacheUpTo(textBuffer: TextBuffer, line: Int) {
        val lineCount = textBuffer.lineCount.coerceAtLeast(0)
        val version = textBuffer.version
        if (lineCount <= 0) {
            invalidate()
            return
        }
        if (version != cachedVersion || cachedLineCount != lineCount || cachedLineDepths.size != lineCount) {
            cachedVersion = version
            cachedLineDepths = IntArray(lineCount)
            cachedLineCount = lineCount
            dirtyFromLine = 0
        }

        val targetLine = line.coerceIn(0, lineCount - 1)
        if (targetLine < dirtyFromLine) return

        val startLine = dirtyFromLine.coerceIn(0, lineCount - 1)
        val startDepth = if (startLine <= 0) {
            0
        } else {
            TextScanKernel.advanceBracketDepth(
                cachedLineDepths[startLine - 1],
                textBuffer.getLine(startLine - 1)
            )
        }

        val blockStartOffset = textBuffer.getLineStart(startLine)
        val blockEndOffset = textBuffer.getLineEnd(targetLine)
        val boundaryDepths = TextScanKernel.computeLineBoundaryBracketDepths(
            startDepth = startDepth,
            text = textBuffer.substring(blockStartOffset, blockEndOffset)
        )
        val coveredLineCount = targetLine - startLine + 1
        for (index in 0 until coveredLineCount) {
            cachedLineDepths[startLine + index] = boundaryDepths[index]
        }
        val nextLine = targetLine + 1
        if (nextLine < lineCount && boundaryDepths.size > coveredLineCount) {
            cachedLineDepths[nextLine] = boundaryDepths[coveredLineCount]
            dirtyFromLine = (nextLine + 1).coerceAtMost(lineCount)
        } else {
            dirtyFromLine = nextLine.coerceAtMost(lineCount)
        }
    }
}
