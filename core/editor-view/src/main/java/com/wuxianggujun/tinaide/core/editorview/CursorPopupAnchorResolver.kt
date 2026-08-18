package com.wuxianggujun.tinaide.core.editorview

import android.graphics.Paint

internal data class CursorPopupAnchor(
    val cursorXInViewportPx: Float,
    val cursorLineTopInViewportPx: Float
)

internal data class CursorVisualAnchor(
    val cursorXInContentPx: Float,
    val cursorLineTopInViewportPx: Float,
    val cursorLineBottomInViewportPx: Float,
    val visualLine: Int
)

internal object CursorPopupAnchorResolver {
    fun resolve(
        state: EditorState,
        contentStartXPx: Float,
        textPaint: Paint,
        lineLayoutCache: EditorLineLayoutCache,
        fontSizePx: Float,
        lineTextProvider: (Int) -> String,
        textScanCache: EditorTextScanCache
    ): CursorPopupAnchor {
        textPaint.typeface = state.typeface
        textPaint.textSize = fontSizePx
        val anchor = resolveCursorVisualAnchor(
            state = state,
            textStartX = contentStartXPx,
            textPaint = textPaint,
            lineLayoutCache = lineLayoutCache,
            lineTextProvider = lineTextProvider,
            textScanCache = textScanCache
        ) ?: return CursorPopupAnchor(
            cursorXInViewportPx = contentStartXPx - state.scrollOffsetXPx,
            cursorLineTopInViewportPx = 0f
        )
        return CursorPopupAnchor(
            cursorXInViewportPx = anchor.cursorXInContentPx - state.scrollOffsetXPx,
            cursorLineTopInViewportPx = anchor.cursorLineTopInViewportPx
        )
    }
}

internal fun resolveCursorVisualAnchor(
    state: EditorState,
    textStartX: Float,
    textPaint: Paint,
    lineLayoutCache: EditorLineLayoutCache,
    lineTextProvider: (Int) -> String,
    foldEndLineInfo: FoldEndLineCursorInfo? = null,
    textScanCache: EditorTextScanCache
): CursorVisualAnchor? {
    if (state.lineHeightPx <= 0f) return null
    if (state.textBuffer.lineCount <= 0) return null

    val maxLine = state.textBuffer.lineCount - 1
    val line = state.cursorPosition.line.coerceIn(0, maxLine)
    val lineText = lineTextProvider(line)
    val column = state.cursorPosition.column.coerceIn(0, lineText.length)
    val visualLine = state.visualLineForPosition(line, column)
    val lineTop = state.visualLineTopInViewport(visualLine)
    return CursorVisualAnchor(
        cursorXInContentPx = resolveCursorContentX(
            state = state,
            textStartX = textStartX,
            textPaint = textPaint,
            lineLayoutCache = lineLayoutCache,
            line = line,
            lineText = lineText,
            column = column,
            visualLine = visualLine,
            foldEndLineInfo = foldEndLineInfo,
            textScanCache = textScanCache
        ),
        cursorLineTopInViewportPx = lineTop,
        cursorLineBottomInViewportPx = lineTop + state.lineHeightPx,
        visualLine = visualLine
    )
}

internal fun resolveCursorContentX(
    state: EditorState,
    textStartX: Float,
    textPaint: Paint,
    lineLayoutCache: EditorLineLayoutCache,
    line: Int,
    lineText: String,
    column: Int,
    visualLine: Int,
    foldEndLineInfo: FoldEndLineCursorInfo?,
    textScanCache: EditorTextScanCache
): Float {
    val safeTabSize = state.config.tabSize.coerceAtLeast(1)
    return if (foldEndLineInfo != null) {
        val startLineText = foldEndLineInfo.foldStartLineText
        val startPrefixLayout = lineLayoutCache.getPrefixLayout(
            state = state,
            line = foldEndLineInfo.foldStartLine,
            lineText = startLineText,
            textVersion = state.textBuffer.version,
            paint = textPaint,
        )
        val safeTrimmedEndCol =
            foldEndLineInfo.foldStartLineTrimmedEndCol.coerceIn(0, startPrefixLayout.length)
        val startLineEndX = startPrefixLayout.prefix[safeTrimmedEndCol]
        val endPrefixLayout = lineLayoutCache.getPrefixLayout(
            state = state,
            line = line,
            lineText = lineText,
            textVersion = state.textBuffer.version,
            paint = textPaint,
        )
        val safeTrimStartCol =
            foldEndLineInfo.endLineTrimStartCol.coerceIn(0, endPrefixLayout.length)
        val safeColumn = column.coerceIn(safeTrimStartCol, endPrefixLayout.length)
        val endLineTrimStartX = endPrefixLayout.prefix[safeTrimStartCol]
        val endLineCursorX = endPrefixLayout.prefix[safeColumn]
        val trailingWidth = resolveMeasuredOrFallbackWidth(
            measuredWidthPx = endLineCursorX - endLineTrimStartX,
            line = line,
            lineText = lineText,
            startColumn = safeTrimStartCol,
            endColumn = safeColumn,
            charWidthPx = state.charWidthPx,
            tabSize = safeTabSize,
            textVersion = state.textBuffer.version,
            textScanCache = textScanCache
        )
        textStartX + startLineEndX +
            foldEndLineInfo.badgeMargin + foldEndLineInfo.badgeWidth + foldEndLineInfo.badgeMargin +
            trailingWidth
    } else {
        val prefixLayout = lineLayoutCache.getPrefixLayout(
            state = state,
            line = line,
            lineText = lineText,
            textVersion = state.textBuffer.version,
            paint = textPaint,
        )
        val segmentStartColumn =
            state.visualLineStartColumn(visualLine).coerceIn(0, prefixLayout.length)
        val safeColumn = column.coerceIn(segmentStartColumn, prefixLayout.length)
        val segmentStartXInText = prefixLayout.segmentStartAdvance(segmentStartColumn)
        val beforeCursorWidth = prefixLayout.textStartAdvance(safeColumn)
        val segmentWidth = resolveMeasuredOrFallbackWidth(
            measuredWidthPx = beforeCursorWidth - segmentStartXInText,
            line = line,
            lineText = lineText,
            startColumn = segmentStartColumn,
            endColumn = safeColumn,
            charWidthPx = state.charWidthPx,
            tabSize = safeTabSize,
            textVersion = state.textBuffer.version,
            textScanCache = textScanCache
        )
        textStartX + segmentWidth
    }
}

private fun resolveMeasuredOrFallbackWidth(
    measuredWidthPx: Float,
    line: Int,
    lineText: String,
    startColumn: Int,
    endColumn: Int,
    charWidthPx: Float,
    tabSize: Int,
    textVersion: Long,
    textScanCache: EditorTextScanCache
): Float {
    val safeMeasuredWidthPx = measuredWidthPx.coerceAtLeast(0f)
    if (endColumn <= startColumn) {
        return safeMeasuredWidthPx
    }
    if (charWidthPx <= 0f) return safeMeasuredWidthPx

    val safeStart = startColumn.coerceIn(0, lineText.length)
    val safeEnd = endColumn.coerceIn(safeStart, lineText.length)
    val visualColumnPrefix = textScanCache.getVisualColumnPrefix(
        line = line,
        lineText = lineText,
        textVersion = textVersion,
        tabSize = tabSize
    )
    val startVisualColumn = visualColumnPrefix[safeStart]
    val endVisualColumn = visualColumnPrefix[safeEnd]
    val widthPx = ((endVisualColumn - startVisualColumn).coerceAtLeast(0)) * charWidthPx
    if (widthPx <= 0f) {
        return safeMeasuredWidthPx
    }

    // Android 真机通常会给出正常的 measuredWidth；这里主要兜底测试/异常运行时里
    // 明显失真的列宽结果，避免弹窗横向锚点被错误压缩到靠近行首的位置。
    return if (safeMeasuredWidthPx <= 0f || safeMeasuredWidthPx < widthPx * 0.5f) {
        widthPx
    } else {
        safeMeasuredWidthPx
    }
}
