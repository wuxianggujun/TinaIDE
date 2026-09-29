package com.wuxianggujun.tinaide.core.editorview

import android.content.Context
import android.widget.OverScroller
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 使用 Android OverScroller 提供更接近原生 View 的惯性滚动手感。
 */
internal class EditorOverScrollerFlingBehavior(
    context: Context,
    private val currentOffsetProvider: () -> Float = { 0f },
    private val minOffsetProvider: () -> Float = { 0f },
    private val maxOffsetProvider: () -> Float,
    private val canScrollProvider: () -> Boolean = { true },
    private val onFlingStarted: () -> Unit = {},
    private val onFlingFinished: () -> Unit = {}
) : FlingBehavior {

    private val scroller = OverScroller(context)

    init {
        // 更长的衰减曲线，提升横向滚动/甩动的连续感。
        scroller.setFriction(SCROLLER_FRICTION)
    }

    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        if (!initialVelocity.isFinite() || abs(initialVelocity) < MIN_START_VELOCITY_PX_PER_SEC) {
            return initialVelocity
        }
        if (!canScrollProvider()) {
            return initialVelocity
        }

        onFlingStarted()
        try {
            scroller.forceFinished(true)

            val min = minOffsetProvider()
            val max = maxOffsetProvider()
            val minOffset = min.coerceAtMost(max)
            val maxOffset = max.coerceAtLeast(min)
            if (maxOffset <= minOffset) {
                return 0f
            }

            val currentOffset = currentOffsetProvider().coerceIn(minOffset, maxOffset)
            // 将 OverScroller 的坐标系映射为“手势 delta 累计位移”，
            // 使 scroller 的每帧 delta 可直接喂给 ScrollScope.scrollBy(delta)。
            val minDisplacement = currentOffset - maxOffset
            val maxDisplacement = currentOffset - minOffset
            val low = minDisplacement.roundToInt()
            val high = maxDisplacement.roundToInt()
            if (high <= low) {
                return 0f
            }

            scroller.fling(
                0,
                0,
                (initialVelocity * VELOCITY_SCALE).roundToInt(),
                0,
                low,
                high,
                0,
                0
            )

            var last = 0
            while (!scroller.isFinished && canScrollProvider()) {
                withFrameNanos { }
                if (!scroller.computeScrollOffset()) break

                val current = scroller.currX
                val delta = (current - last).toFloat()
                if (abs(delta) >= DELTA_EPSILON_PX) {
                    val consumed = scrollBy(delta)
                    if (abs(consumed - delta) > CONSUME_EPSILON_PX) {
                        scroller.forceFinished(true)
                        return 0f
                    }
                }
                last = current
            }
            return 0f
        } finally {
            onFlingFinished()
        }
    }

    private companion object {
        private const val SCROLLER_FRICTION = 0.0105f
        private const val VELOCITY_SCALE = 1.2f
        private const val MIN_START_VELOCITY_PX_PER_SEC = 25f
        private const val DELTA_EPSILON_PX = 0.01f
        private const val CONSUME_EPSILON_PX = 0.25f
    }
}
