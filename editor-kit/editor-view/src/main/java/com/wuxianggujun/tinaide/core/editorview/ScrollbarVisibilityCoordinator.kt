package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class ScrollbarVisibilityCoordinator(
    private val state: EditorState,
    private val coroutineScope: CoroutineScope,
    private val isScrollbarDragActive: () -> Boolean
) {
    val alpha = Animatable(0f)
    private var fadeJob: Job? = null

    fun trigger(keepVisible: Boolean = false) {
        if (state.maxVerticalScrollOffsetPx() <= 0f && state.maxHorizontalScrollOffsetPx() <= 0f) {
            return
        }
        if (keepVisible) {
            fadeJob?.cancel()
            fadeJob = null
            if (alpha.value < 0.995f) {
                coroutineScope.launch {
                    alpha.snapTo(1f)
                }
            }
            return
        }
        fadeJob?.cancel()
        fadeJob = coroutineScope.launch {
            alpha.snapTo(1f)
            val fadeDelayMs = state.config.scrollbarFadeDelayMs.coerceIn(120L, 3_000L)
            val fadeDurationMs = state.config.scrollbarFadeDurationMs.coerceIn(80L, 2_000L)
            delay(fadeDelayMs)
            if (!isScrollbarDragActive()) {
                alpha.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = fadeDurationMs.toInt())
                )
            }
        }
    }

    fun onDispose() {
        fadeJob?.cancel()
        fadeJob = null
    }
}
