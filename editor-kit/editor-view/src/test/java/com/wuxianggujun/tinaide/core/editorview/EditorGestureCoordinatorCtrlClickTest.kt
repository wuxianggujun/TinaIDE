package com.wuxianggujun.tinaide.core.editorview

import android.content.Context
import android.graphics.Paint
import androidx.compose.foundation.gestures.TransformableState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import com.wuxianggujun.tinaide.core.textengine.TextChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class EditorGestureCoordinatorCtrlClickTest {

    @Test
    fun onTap_shouldRequestGotoDefinitionWhenCtrlClickIdentifier() {
        val fixture = createFixture("alpha  beta")
        var requestCount = 0
        fixture.state.onRequestGotoDefinition = { requestCount++ }

        fixture.coordinator.onTap(
            position = fixture.positionForColumn(column = 2),
            isCtrlPressed = true
        )

        assertThat(requestCount).isEqualTo(1)
        assertThat(fixture.state.cursorColumn).isEqualTo(2)
    }

    @Test
    fun onTap_shouldNotRequestGotoDefinitionWhenCtrlClickWhitespace() {
        val fixture = createFixture("alpha  beta")
        var requestCount = 0
        fixture.state.onRequestGotoDefinition = { requestCount++ }

        fixture.coordinator.onTap(
            position = fixture.positionForColumn(column = 6),
            isCtrlPressed = true
        )

        assertThat(requestCount).isEqualTo(0)
        assertThat(fixture.state.cursorColumn).isEqualTo(6)
    }

    @Test
    fun onTap_shouldNotRequestGotoDefinitionWhenCtrlIsNotPressed() {
        val fixture = createFixture("alpha  beta")
        var requestCount = 0
        fixture.state.onRequestGotoDefinition = { requestCount++ }

        fixture.coordinator.onTap(
            position = fixture.positionForColumn(column = 2),
            isCtrlPressed = false
        )

        assertThat(requestCount).isEqualTo(0)
        assertThat(fixture.state.cursorColumn).isEqualTo(2)
    }

    private fun createFixture(text: String): GestureFixture {
        val state = EditorState(RopeTextBuffer(text))
        val density = Density(1f)
        val lineHeightPx = 20f
        val textStartX = 24f
        state.updateMetrics(
            lineHeightPx = lineHeightPx,
            charWidthPx = 10f,
            viewportHeightPx = 200f,
            viewportWidthPx = 400f,
            contentStartXPx = textStartX
        )

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = state.typeface
            textSize = with(density) { state.fontSizeSp.sp.toPx() }
        }
        val lineNumberPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val interactionController = EditorInteractionController(
            state = state,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            focusRequester = FocusRequester(),
            keyboardController = null,
            inputMethodManager = null
        ).apply {
            bindInputHostView(
                EditorInputHostView(ApplicationProvider.getApplicationContext<Context>())
            )
        }
        val coordinator = EditorGestureCoordinator(
            state = state,
            renderer = FakeRenderEngine(textStartX),
            lineNumberPaint = lineNumberPaint,
            textPaint = textPaint,
            density = density,
            lineLayoutCache = EditorLineLayoutCache(),
            gestureHandler = EditorGestureHandler(uptimeMillisProvider = { 1_000L }),
            transformableState = TransformableState { _, _, _ -> },
            interactionController = interactionController,
            onContextMenuVisibleChange = {},
            onContextMenuOffsetChange = {}
        )
        return GestureFixture(
            state = state,
            coordinator = coordinator,
            textPaint = textPaint,
            textStartX = textStartX,
            lineHeightPx = lineHeightPx,
            lineText = text
        )
    }

    private data class GestureFixture(
        val state: EditorState,
        val coordinator: EditorGestureCoordinator,
        val textPaint: Paint,
        val textStartX: Float,
        val lineHeightPx: Float,
        val lineText: String
    ) {
        fun positionForColumn(column: Int): Offset {
            val safeColumn = column.coerceIn(0, lineText.length)
            val x = textStartX + textPaint.measureText(lineText, 0, safeColumn)
            return Offset(x, lineHeightPx / 2f)
        }
    }

    private class FakeRenderEngine(
        private val textStartX: Float
    ) : EditorRenderEngine {
        override fun render(
            drawScope: DrawScope,
            state: EditorState,
            textPaint: Paint,
            lineNumberPaint: Paint
        ) = Unit

        override fun renderCursorOverlay(
            drawScope: DrawScope,
            state: EditorState,
            textPaint: Paint,
            lineNumberPaint: Paint
        ) = Unit

        override fun contentStartX(state: EditorState, lineNumberPaint: Paint): Float = textStartX

        override fun hitZones(state: EditorState, lineNumberPaint: Paint): EditorHitZones = EditorHitZones(
            lineNumberEndX = 0f,
            gutterEndX = 0f,
            textStartX = textStartX
        )

        override fun isFoldBadgeHit(
            docLine: Int,
            contentX: Float,
            state: EditorState,
            textPaint: Paint
        ): Boolean = false

        override fun resolveFoldEndLineOffset(
            foldStartLine: Int,
            contentX: Float,
            state: EditorState,
            textPaint: Paint
        ): Int = -1

        override fun performanceSnapshot(): EditorRenderPerformanceSnapshot = EditorRenderPerformanceSnapshot(
            totalRenderedFrames = 0,
            slowRenderedFrames = 0,
            lastRenderDurationMs = 0,
            lastVisibleLineCount = 0,
            lastFrameCacheHits = 0,
            lastFrameCacheMisses = 0,
            totalCacheHits = 0,
            totalCacheMisses = 0,
            totalCacheHitRatePercent = 0.0,
            textLineCacheSize = 0,
            textScanCacheSize = 0,
            lineLayoutCacheEntryCount = 0,
            lineLayoutCacheFloatCount = 0
        )

        override fun invalidateCache() = Unit

        override fun applyTextChange(
            change: TextChange,
            currentVersion: Long,
            currentLineCount: Int
        ) = Unit
    }
}
