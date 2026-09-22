package com.wuxianggujun.tinaide.core.editorview

import android.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.wuxianggujun.tinaide.core.textengine.TextChange

internal interface EditorRenderEngine {
    fun render(
        drawScope: DrawScope,
        state: EditorState,
        textPaint: Paint,
        lineNumberPaint: Paint
    )

    fun renderCursorOverlay(
        drawScope: DrawScope,
        state: EditorState,
        textPaint: Paint,
        lineNumberPaint: Paint
    )

    fun contentStartX(state: EditorState, lineNumberPaint: Paint): Float

    fun hitZones(state: EditorState, lineNumberPaint: Paint): EditorHitZones

    fun isFoldBadgeHit(docLine: Int, contentX: Float, state: EditorState, textPaint: Paint): Boolean

    /**
     * 将折叠装饰区域中末行文本部分的点击解析为文档 offset。
     * 如果 contentX 不在末行文本区域内，返回 -1。
     */
    fun resolveFoldEndLineOffset(
        foldStartLine: Int,
        contentX: Float,
        state: EditorState,
        textPaint: Paint
    ): Int

    fun performanceSnapshot(): EditorRenderPerformanceSnapshot

    fun invalidateCache()

    fun applyTextChange(change: TextChange, currentVersion: Long, currentLineCount: Int)
}

data class EditorRenderPerformanceSnapshot(
    val totalRenderedFrames: Long,
    val slowRenderedFrames: Long,
    val lastRenderDurationMs: Long,
    val lastVisibleLineCount: Int,
    val lastFrameCacheHits: Int,
    val lastFrameCacheMisses: Int,
    val totalCacheHits: Long,
    val totalCacheMisses: Long,
    val totalCacheHitRatePercent: Double,
    val textLineCacheSize: Int,
    val textScanCacheSize: Int,
    val lineLayoutCacheEntryCount: Int,
    val lineLayoutCacheFloatCount: Int,
    val renderPlanCacheEntryCount: Int = 0,
    val renderPlanCacheCharCount: Int = 0,
    val renderPlanCacheElementCount: Int = 0,
    val totalRenderPlanBuilds: Long = 0L,
    val totalRenderPlanCacheHits: Long = 0L,
)

internal data class EditorHitZones(
    val lineNumberEndX: Float,
    val gutterEndX: Float,
    val textStartX: Float
)
