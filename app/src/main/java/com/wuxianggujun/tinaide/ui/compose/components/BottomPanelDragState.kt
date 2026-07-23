package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.coroutines.coroutineContext

/**
 * 底部面板高度预设
 *
 * 定义不同场景下的面板高度比例
 */
object PanelHeightPreset {
    /** 收起状态 */
    const val COLLAPSED = 0f

    /** 默认展开高度（构建日志、诊断等） */
    const val DEFAULT = 0.45f

    /** 终端默认高度（较小，保持编辑器可见） */
    const val TERMINAL = 0.30f

    /** 全屏高度 */
    const val FULL_SCREEN = 1.0f
}

/**
 * 底部面板状态管理
 *
 * 提供流畅的跟手拖拽体验，支持：
 * - 实时跟随手指移动
 * - 松手后根据速度和位置决定目标状态
 * - 平滑的弹簧动画过渡
 *
 * 重构说明：
 * - 移除 rememberSaveable，不再跨会话保存高度状态
 * - 每次打开应用从收起状态开始
 * - 简化高度预设，使用 PanelHeightPreset 统一管理
 */
@Stable
class BottomPanelDragState(
    initialExpanded: Boolean = false,
    minHeight: Float = 0f,
    maxHeight: Float = 300f
) {
    var minHeight: Float = minHeight
        private set

    var maxHeight: Float = maxHeight
        private set

    internal val heightAnimatable = Animatable(
        if (initialExpanded) maxHeight else minHeight
    )

    /** 面板是否处于展开状态（高度超过最小值的 10%） */
    val isExpanded: Boolean
        get() = heightAnimatable.value > minHeight + (maxHeight - minHeight) * 0.1f

    /** 当前高度（像素） */
    val currentHeight: Float
        get() = heightAnimatable.value

    /** 是否正在拖拽 */
    var isDragging by mutableStateOf(false)
        internal set

    /** 是否接近全屏（超过 70%） */
    val isNearFullScreen: Boolean
        get() = currentHeight > maxHeight * 0.7f

    // ============ 高度控制方法 ============

    /**
     * 展开到全屏
     */
    suspend fun expandToFullScreen() {
        animateToFraction(PanelHeightPreset.FULL_SCREEN)
    }

    /**
     * 展开到默认高度（用于构建日志、诊断等）
     */
    suspend fun expandToDefault() {
        animateToFraction(PanelHeightPreset.DEFAULT)
    }

    /**
     * 展开到终端默认高度
     */
    suspend fun expandToTerminal() {
        animateToFraction(PanelHeightPreset.TERMINAL)
    }

    /**
     * 收起面板
     */
    suspend fun collapse() {
        animateHeightTo(
            targetHeight = minHeight,
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        )
    }

    /**
     * 立即收起（无动画）
     */
    suspend fun collapseImmediate() {
        heightAnimatable.snapTo(minHeight)
    }

    internal suspend fun updateBounds(minHeight: Float, maxHeight: Float) {
        this.minHeight = minHeight
        this.maxHeight = maxHeight
        heightAnimatable.snapTo(heightAnimatable.value.coerceIn(minHeight, maxHeight))
    }

    /**
     * 切换展开/收起状态
     */
    suspend fun toggle() {
        if (isExpanded) collapse() else expandToDefault()
    }

    // ============ 内部方法 ============

    /**
     * 动画到指定比例的高度
     */
    private suspend fun animateToFraction(fraction: Float) {
        val targetHeight = minHeight + (maxHeight - minHeight) * fraction.coerceIn(0f, 1f)
        animateHeightTo(
            targetHeight = targetHeight,
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        )
    }

    /**
     * 立即跳转到指定比例的高度（无动画）
     */
    suspend fun snapToFraction(fraction: Float) {
        val targetHeight = minHeight + (maxHeight - minHeight) * fraction.coerceIn(0f, 1f)
        heightAnimatable.snapTo(targetHeight)
    }

    /**
     * 动画到指定高度（像素）
     */
    suspend fun animateToHeight(targetHeight: Float) {
        val clamped = targetHeight.coerceIn(minHeight, maxHeight)
        animateHeightTo(
            targetHeight = clamped,
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        )
    }

    /**
     * 命令面板 / lifecycleScope 等非 Composition 协程没有 [MonotonicFrameClock]，
     * 此时 [Animatable.animateTo] 会抛 IllegalStateException；退化为 snap，保证可展开。
     */
    private suspend fun animateHeightTo(
        targetHeight: Float,
        dampingRatio: Float,
        stiffness: Float,
        initialVelocity: Float = 0f,
    ) {
        if (coroutineContext[MonotonicFrameClock] == null) {
            heightAnimatable.snapTo(targetHeight)
            return
        }
        heightAnimatable.animateTo(
            targetValue = targetHeight,
            animationSpec = spring(
                dampingRatio = dampingRatio,
                stiffness = stiffness,
            ),
            initialVelocity = initialVelocity,
        )
    }

    /**
     * 立即跳转到指定高度（无动画）
     */
    suspend fun snapToHeight(targetHeight: Float) {
        val clamped = targetHeight.coerceIn(minHeight, maxHeight)
        heightAnimatable.snapTo(clamped)
    }

    // ============ 拖拽处理 ============

    /**
     * 拖拽移动
     */
    internal suspend fun dragBy(delta: Float) {
        val newHeight = (heightAnimatable.value - delta).coerceIn(minHeight, maxHeight)
        heightAnimatable.snapTo(newHeight)
    }

    /**
     * 拖拽结束，根据速度和位置决定目标状态
     *
     * 三个吸附目标：收起（0%）、默认（DEFAULT=45%）、全屏（100%）
     * - 快速向上/向下滑动：直接吸附到全屏/收起
     * - 慢速释放：吸附到距当前高度最近的预设点（收起、默认、全屏）
     */
    internal suspend fun settle(velocity: Float) {
        val velocityThreshold = 1500f
        val snapThreshold = 20f
        val currentHeight = heightAnimatable.value
        val defaultHeight = minHeight + (maxHeight - minHeight) * PanelHeightPreset.DEFAULT

        val targetHeight = when {
            // 快速向上滑动 -> 全屏
            velocity < -velocityThreshold -> maxHeight
            // 快速向下滑动 -> 收起
            velocity > velocityThreshold -> minHeight
            // 接近最小高度（收起临界）-> 收起
            currentHeight < minHeight + snapThreshold -> minHeight
            // 接近最大高度（全屏临界）-> 全屏
            currentHeight > maxHeight - snapThreshold -> maxHeight
            // 慢速释放：吸附到最近的三档预设点（收起 / 默认 / 全屏）
            else -> {
                val distToMin = currentHeight - minHeight
                val distToDefault = kotlin.math.abs(currentHeight - defaultHeight)
                val distToMax = maxHeight - currentHeight
                when (minOf(distToMin, distToDefault, distToMax)) {
                    distToMin -> minHeight
                    distToMax -> maxHeight
                    else -> defaultHeight
                }
            }
        }

        if (targetHeight == currentHeight) return

        animateHeightTo(
            targetHeight = targetHeight,
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
            initialVelocity = -velocity,
        )
    }
}

/**
 * 创建并记住底部面板状态
 *
 * 注意：不使用 rememberSaveable，不跨会话保存高度状态
 * 可用高度变化时更新已有状态边界，避免键盘弹出时重建为收起状态
 */
@Composable
fun rememberBottomPanelDragState(
    initialExpanded: Boolean = false,
    minHeight: Dp = 0.dp,
    maxHeight: Dp = 300.dp
): BottomPanelDragState {
    val density = LocalDensity.current
    val minHeightPx = with(density) { minHeight.toPx() }
    val maxHeightPx = with(density) { maxHeight.toPx() }

    // IME 或窗口尺寸变化会改变可用高度，这里只同步边界，不重建状态。
    val state = remember(initialExpanded) {
        BottomPanelDragState(initialExpanded, minHeightPx, maxHeightPx)
    }
    LaunchedEffect(minHeightPx, maxHeightPx) {
        state.updateBounds(minHeightPx, maxHeightPx)
    }
    return state
}
