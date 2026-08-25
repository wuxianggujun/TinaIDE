package com.wuxianggujun.tinaide.core.editorview

import android.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect

internal class DiagnosticRenderer(
    private val pathBuilder: DiagnosticWavePathBuilder = DiagnosticWavePathBuilder()
) {

    private companion object {
        private const val WAVE_LENGTH_DP = 18f
        private const val WAVE_AMPLITUDE_DP = 4f
        private const val WAVE_STROKE_WIDTH_DP = 0.9f
        private const val BASELINE_GAP_RATIO = 0.06f
    }

    private data class LineSegment(
        val startColumn: Int,
        val endColumn: Int,
        val severity: DiagnosticSeverity
    )

    private var cachedDensity = 0f
    private var waveLengthPx = 0f
    private var desiredAmplitudePx = 0f
    private var strokeWidthPx = 0f
    private var cachedStroke = Stroke(width = 1f)

    private var segmentCacheDiagRef: Map<Int, List<EditorDiagnostic>>? = null
    private var segmentCacheTextVersion = -1L
    private val segmentCacheByLine = HashMap<Int, List<LineSegment>>(16)

    fun drawDiagnostics(
        drawScope: DrawScope,
        frameContext: EditorRenderFrameContext,
        textStartX: Float,
        textPaint: Paint,
        lineLayoutCache: EditorLineLayoutCache
    ) {
        val state = frameContext.state
        val density = drawScope.density
        if (density != cachedDensity) {
            cachedDensity = density
            waveLengthPx = WAVE_LENGTH_DP * density
            desiredAmplitudePx = WAVE_AMPLITUDE_DP * density
            strokeWidthPx = (WAVE_STROKE_WIDTH_DP * density).coerceAtLeast(0.5f)
            cachedStroke = Stroke(width = strokeWidthPx)
        }

        val diagByLine = state.diagnosticsByLine
        val textVersion = frameContext.textVersion
        if (diagByLine !== segmentCacheDiagRef || textVersion != segmentCacheTextVersion) {
            segmentCacheDiagRef = diagByLine
            segmentCacheTextVersion = textVersion
            segmentCacheByLine.clear()
        }

        val stroke = cachedStroke
        val waveLength = waveLengthPx
        val desiredAmplitude = desiredAmplitudePx
        val sw = strokeWidthPx

        state.visibleLines.forEach { visualLine ->
            val line = state.docLineForVisualLine(visualLine)
            if (line >= state.textBuffer.lineCount) return@forEach
            val lineText = frameContext.lineText(line)
            if (lineText.isEmpty()) return@forEach
            val diagnostics = diagByLine[line].orEmpty()
            if (diagnostics.isEmpty()) return@forEach

            val segments = segmentCacheByLine.getOrPut(line) {
                buildLineSegments(diagnostics, lineText.length)
            }
            if (segments.isEmpty()) return@forEach

            val visualStartColumn = state.visualLineStartColumn(visualLine).coerceIn(0, lineText.length)
            val visualEndColumn = state.visualLineEndColumn(visualLine).coerceIn(visualStartColumn, lineText.length)
            if (visualEndColumn <= visualStartColumn) return@forEach

            val prefixLayout = lineLayoutCache.getPrefixLayout(
                state = state,
                line = line,
                lineText = lineText,
                textVersion = textVersion,
                paint = textPaint,
            )
            val segmentStartXInText = prefixLayout.segmentStartAdvance(visualStartColumn)
            val lineTop = state.visualLineTopInViewport(visualLine)
            val lineBottom = lineTop + state.lineHeightPx
            val baselineY = lineTop + state.lineHeightPx * 0.78f

            val maxAmplitude = ((lineBottom - baselineY) * (1f - BASELINE_GAP_RATIO)).coerceAtLeast(0f)
            val waveAmplitude = desiredAmplitude.coerceIn(0f, maxAmplitude)
            if (waveAmplitude <= 0.01f) return@forEach

            val baseY = lineBottom
            val scheme = state.colorScheme

            val clipTop = (lineTop - waveAmplitude - sw).coerceAtLeast(0f)
            val clipBottom = (lineBottom + waveAmplitude + sw).coerceAtMost(drawScope.size.height)

            drawScope.clipRect(
                left = textStartX,
                top = clipTop,
                right = drawScope.size.width + state.scrollOffsetXPx,
                bottom = clipBottom
            ) {
                segments.forEach { segment ->
                    val startColumn = maxOf(segment.startColumn, visualStartColumn).coerceIn(0, lineText.length)
                    val endColumn = minOf(segment.endColumn, visualEndColumn).coerceIn(startColumn, lineText.length)
                    val startX =
                        textStartX + prefixLayout.textStartAdvance(startColumn) - segmentStartXInText
                    val endX =
                        textStartX + prefixLayout.textEndAdvance(endColumn) - segmentStartXInText
                    if (endX <= startX) return@forEach

                    val color = when (segment.severity) {
                        DiagnosticSeverity.ERROR -> scheme.diagnosticError
                        DiagnosticSeverity.WARNING -> scheme.diagnosticWarning
                        DiagnosticSeverity.INFO -> scheme.diagnosticInfo
                        DiagnosticSeverity.HINT -> scheme.diagnosticHint
                    }

                    val path = pathBuilder.build(
                        startX = startX,
                        endX = endX,
                        baseY = baseY,
                        waveLength = waveLength,
                        waveAmplitude = waveAmplitude
                    )

                    drawPath(
                        path = path,
                        color = color,
                        style = stroke
                    )
                }
            }
        }
    }

    private fun buildLineSegments(
        diagnostics: List<EditorDiagnostic>,
        lineLength: Int
    ): List<LineSegment> {
        if (lineLength <= 0 || diagnostics.isEmpty()) return emptyList()
        val normalized = diagnostics.mapNotNull { diagnostic ->
            val start = diagnostic.startColumn.coerceIn(0, lineLength)
            val end = diagnostic.endColumn.coerceIn(start, lineLength)
            if (end <= start) {
                null
            } else {
                LineSegment(startColumn = start, endColumn = end, severity = diagnostic.severity)
            }
        }
        if (normalized.isEmpty()) return emptyList()

        val boundaries = sortedSetOf<Int>()
        normalized.forEach { segment ->
            boundaries.add(segment.startColumn)
            boundaries.add(segment.endColumn)
        }
        if (boundaries.size <= 1) return emptyList()
        val boundaryList = boundaries.toList()

        val resolved = ArrayList<LineSegment>(boundaryList.size)
        for (i in 0 until boundaryList.lastIndex) {
            val segStart = boundaryList[i]
            val segEnd = boundaryList[i + 1]
            if (segEnd <= segStart) continue

            var winnerSeverity: DiagnosticSeverity? = null
            var winnerPriority = Int.MIN_VALUE
            normalized.forEach { segment ->
                if (segment.startColumn < segEnd && segment.endColumn > segStart) {
                    val priority = severityPriority(segment.severity)
                    if (priority > winnerPriority) {
                        winnerPriority = priority
                        winnerSeverity = segment.severity
                    }
                }
            }

            val severity = winnerSeverity ?: continue
            val last = resolved.lastOrNull()
            if (last != null && last.severity == severity && segStart <= last.endColumn) {
                resolved[resolved.lastIndex] = last.copy(endColumn = maxOf(last.endColumn, segEnd))
            } else {
                resolved.add(
                    LineSegment(
                        startColumn = segStart,
                        endColumn = segEnd,
                        severity = severity
                    )
                )
            }
        }
        return resolved
    }

    private fun severityPriority(severity: DiagnosticSeverity): Int = when (severity) {
        DiagnosticSeverity.ERROR -> 4
        DiagnosticSeverity.WARNING -> 3
        DiagnosticSeverity.INFO -> 2
        DiagnosticSeverity.HINT -> 1
    }
}
