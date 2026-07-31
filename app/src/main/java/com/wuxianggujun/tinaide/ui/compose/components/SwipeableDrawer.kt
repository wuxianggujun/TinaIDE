package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.logging.GestureTrace
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 可滑动侧滑栏状态
 *
 * 专为解决 AndroidView（如 CodeEditor）与 Compose 手势冲突而设计。
 * 支持边缘滑动打开、拖拽关闭、点击遮罩关闭等交互。
 * 宽度由调用方通过 `drawerWidth` 控制；尺寸变化时会保留当前打开进度。
 */
@Stable
class SwipeableDrawerState(
    initialOpen: Boolean = false,
    initialSelectedTab: DrawerTab = DrawerTab.FILES,
    initialDrawerWidthPx: Float,
    private val coroutineScope: CoroutineScope
) {
    private var animationJob: Job? = null
    private var animationTargetOpen: Boolean? = null

    /**
     * 当前侧滑栏宽度（像素）
     */
    var drawerWidthPx by mutableFloatStateOf(initialDrawerWidthPx)
        private set

    /**
     * 侧滑栏偏移量（0 = 完全关闭，drawerWidthPx = 完全打开）
     */
    var offsetX by mutableFloatStateOf(if (initialOpen) initialDrawerWidthPx else 0f)
        private set

    /**
     * 是否正在拖拽
     */
    var isDragging by mutableStateOf(false)
        private set

    /**
     * 当前侧栏内容 Tab（文件 / 符号 / Git / AI）。
     * 由命令面板等外部入口写入，避免只改本地 state 打不开「符号」。
     */
    var selectedTab by mutableStateOf(initialSelectedTab)

    /**
     * 是否打开
     */
    val isOpen: Boolean
        get() = offsetX > drawerWidthPx * 0.5f

    /**
     * 是否完全关闭
     */
    val isClosed: Boolean
        get() = offsetX <= 0f

    /**
     * 打开进度（0-1）
     */
    val progress: Float
        get() = if (drawerWidthPx > 0f) (offsetX / drawerWidthPx).coerceIn(0f, 1f) else 0f

    /**
     * 打开侧滑栏
     */
    fun open() {
        animateToOpenState(targetOpen = true)
    }

    /**
     * 打开侧栏并切到指定 Tab（命令「符号」等入口用）。
     */
    fun open(tab: DrawerTab) {
        selectedTab = tab
        open()
    }

    /**
     * 关闭侧滑栏
     */
    fun close() {
        animateToOpenState(targetOpen = false)
    }

    /**
     * 切换侧滑栏状态
     */
    fun toggle() {
        if (isOpen) close() else open()
    }

    /**
     * 若已在目标 Tab 则关闭，否则打开并切 Tab。
     */
    fun toggleTab(tab: DrawerTab) {
        if (isOpen && selectedTab == tab) {
            close()
        } else {
            open(tab)
        }
    }

    /**
     * 同步窗口尺寸变化，同时保留当前打开进度。
     * MainActivity 自行处理 screenSize 配置变化，旋转时不会重建此状态对象。
     */
    internal fun updateDrawerWidth(newDrawerWidthPx: Float) {
        if (
            !newDrawerWidthPx.isFinite() ||
            newDrawerWidthPx <= 0f ||
            newDrawerWidthPx == drawerWidthPx
        ) {
            return
        }

        val previousProgress = progress
        val activeAnimationTarget = if (animationJob?.isActive == true) {
            animationTargetOpen
        } else {
            null
        }
        animationJob?.cancel()
        drawerWidthPx = newDrawerWidthPx
        offsetX = newDrawerWidthPx * previousProgress
        activeAnimationTarget?.let(::animateToOpenState)
    }

    /**
     * 开始拖拽
     */
    internal fun startDrag() {
        animationJob?.cancel()
        animationTargetOpen = null
        isDragging = true
    }

    /**
     * 拖拽中
     */
    internal fun drag(delta: Float) {
        offsetX = (offsetX + delta).coerceIn(0f, drawerWidthPx)
    }

    /**
     * 结束拖拽
     */
    internal fun endDrag(velocity: Float) {
        isDragging = false
        val targetOpen = when {
            // 快速滑动：根据速度方向决定
            abs(velocity) > 500f -> velocity > 0
            // 慢速滑动：根据当前位置决定
            else -> offsetX > drawerWidthPx * 0.5f
        }
        animateToOpenState(targetOpen)
    }

    private fun animateToOpenState(targetOpen: Boolean) {
        animationJob?.cancel()
        animationTargetOpen = targetOpen
        animationJob = coroutineScope.launch {
            animateTo(if (targetOpen) drawerWidthPx else 0f)
        }
    }

    /**
     * 动画到目标位置
     */
    private suspend fun animateTo(
        targetValue: Float,
        animationSpec: AnimationSpec<Float> = SpringSpec(stiffness = 400f)
    ) {
        animate(
            initialValue = offsetX,
            targetValue = targetValue,
            animationSpec = animationSpec
        ) { value, _ ->
            offsetX = value
        }
    }
}

/**
 * 记住可滑动侧滑栏状态
 */
@Composable
fun rememberSwipeableDrawerState(
    initialOpen: Boolean = false,
    drawerWidth: Dp = 300.dp
): SwipeableDrawerState {
    val density = LocalDensity.current
    val drawerWidthPx = with(density) { drawerWidth.toPx() }
    val scope = rememberCoroutineScope()

    val saver = listSaver<SwipeableDrawerState, Any>(
        save = { state -> listOf(state.isOpen, state.selectedTab.name) },
        restore = { saved ->
            val restoredTab = (saved.getOrNull(1) as? String)
                ?.let { name -> DrawerTab.entries.firstOrNull { it.name == name } }
                ?: DrawerTab.FILES
            SwipeableDrawerState(
                initialOpen = saved.firstOrNull() as? Boolean ?: initialOpen,
                initialSelectedTab = restoredTab,
                initialDrawerWidthPx = drawerWidthPx,
                coroutineScope = scope,
            )
        },
    )

    val state = rememberSaveable(saver = saver) {
        SwipeableDrawerState(
            initialOpen = initialOpen,
            initialDrawerWidthPx = drawerWidthPx,
            coroutineScope = scope,
        )
    }
    SideEffect {
        state.updateDrawerWidth(drawerWidthPx)
    }
    return state
}

/**
 * 可滑动侧滑栏组件
 *
 * 专为解决 AndroidView（如 CodeEditor）与 Compose ModalNavigationDrawer 手势冲突而设计。
 *
 * 特点：
 * - 完全自定义手势处理，避免与 AndroidView 的缩放/滑动手势冲突
 * - 支持从左边缘滑动打开
 * - 支持在侧滑栏上滑动关闭
 * - 支持点击遮罩关闭
 * - 流畅的弹簧动画效果
 * - 自动适配状态栏
 * - 支持由调用方配置初始宽度
 *
 * @param state 侧滑栏状态
 * @param edgeWidth 边缘触摸区域宽度（用于打开手势）
 * @param scrimColor 遮罩颜色
 * @param drawerContent 侧滑栏内容
 * @param content 主内容
 */
@Composable
fun SwipeableDrawer(
    state: SwipeableDrawerState,
    modifier: Modifier = Modifier,
    edgeWidth: Dp = 24.dp,
    scrimColor: Color = Color.Black.copy(alpha = 0.32f),
    drawerContent: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    val edgeWidthPx = with(density) { edgeWidth.toPx() }
    // 从 state 获取当前宽度，转换为 Dp
    val currentDrawerWidth = with(density) { state.drawerWidthPx.toDp() }

    Box(modifier = modifier.fillMaxSize()) {
        // 主内容
        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
            content()

            // 仅在屏幕边缘区域处理"打开侧滑栏"的手势，避免对 AndroidView（如 CodeEditor）的滚动/惯性造成干扰
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(edgeWidth)
                    .align(Alignment.CenterStart)
                    .edgeDragGesture(
                        state = state,
                        edgeWidthPx = edgeWidthPx
                    )
            )
        }

        // 遮罩层（仅在侧滑栏打开时显示）
        if (state.progress > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(scrimColor.copy(alpha = scrimColor.alpha * state.progress))
                    .pointerInput(state) {
                        val component = "DrawerScrim"
                        var lastLogUptimeMillis = 0L
                        detectHorizontalDragGestures(
                            onDragStart = { pos ->
                                if (GestureTrace.isEnabled()) {
                                    GestureTrace.w(component, "dragStart pos=(${"%.1f".format(pos.x)},${"%.1f".format(pos.y)}) off=${"%.1f".format(state.offsetX)}")
                                }
                                state.startDrag()
                            },
                            onDragEnd = {
                                if (GestureTrace.isEnabled()) {
                                    GestureTrace.w(component, "dragEnd off=${"%.1f".format(state.offsetX)}")
                                }
                                state.endDrag(0f)
                            },
                            onDragCancel = {
                                if (GestureTrace.isEnabled()) {
                                    GestureTrace.w(component, "dragCancel off=${"%.1f".format(state.offsetX)}")
                                }
                                state.endDrag(0f)
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                if (GestureTrace.isEnabled()) {
                                    val now = change.uptimeMillis
                                    if (now - lastLogUptimeMillis >= 120) {
                                        lastLogUptimeMillis = now
                                        GestureTrace.d(
                                            component,
                                            "drag dX=${"%.1f".format(dragAmount)} pos=(${"%.1f".format(change.position.x)},${"%.1f".format(change.position.y)}) off=${"%.1f".format(state.offsetX)}"
                                        )
                                    }
                                }
                                state.drag(dragAmount)
                            }
                        )
                    }
                    .pointerInput(state) {
                        // 点击遮罩关闭
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.any { it.pressed }) {
                                    val change = event.changes.first()
                                    // 如果点击在侧滑栏外部，关闭侧滑栏
                                    if (change.position.x > state.offsetX) {
                                        change.consume()
                                        state.close()
                                    }
                                }
                            }
                        }
                    }
            )
        }

        // 侧滑栏
        // 使用 systemBars 来同时处理状态栏和导航栏的安全区域
        // 这样可以避免底部标签栏与系统小白条重叠
        Box(
            modifier = Modifier
                .offset { IntOffset((state.offsetX - state.drawerWidthPx).roundToInt(), 0) }
                .width(currentDrawerWidth)
                .fillMaxHeight()
                .shadow(if (state.progress > 0f) 16.dp else 0.dp)
                .background(MaterialTheme.colorScheme.surface)
                .windowInsetsPadding(WindowInsets.systemBars)
                .drawerDragGesture(state)
        ) {
            drawerContent()
        }
    }
}

private fun Modifier.edgeDragGesture(
    state: SwipeableDrawerState,
    edgeWidthPx: Float
): Modifier {
    return pointerInput(state, edgeWidthPx) {
        val velocityTracker = VelocityTracker()
        val component = "DrawerEdge"
        var isEdgeDrag = false
        var lastLogUptimeMillis = 0L

        detectHorizontalDragGestures(
            onDragStart = { startOffset ->
                // 只有在边缘区域且侧滑栏关闭时才拦截手势
                isEdgeDrag = state.isClosed && !state.isDragging && startOffset.x <= edgeWidthPx
                if (isEdgeDrag) {
                    if (GestureTrace.isEnabled()) {
                        GestureTrace.w(
                            component,
                            "dragStart x=${"%.1f".format(startOffset.x)} edgeWidthPx=${"%.1f".format(edgeWidthPx)} off=${"%.1f".format(state.offsetX)}"
                        )
                    }
                    velocityTracker.resetTracking()
                    state.startDrag()
                }
                // 不在边缘区域时不拦截，让事件传递给下层（编辑器）
            },
            onDragEnd = {
                if (!isEdgeDrag) return@detectHorizontalDragGestures
                val velocity = velocityTracker.calculateVelocity().x
                if (GestureTrace.isEnabled()) {
                    GestureTrace.w(component, "dragEnd vX=${"%.1f".format(velocity)} off=${"%.1f".format(state.offsetX)}")
                }
                state.endDrag(velocity)
                isEdgeDrag = false
            },
            onDragCancel = {
                if (!isEdgeDrag) return@detectHorizontalDragGestures
                if (GestureTrace.isEnabled()) {
                    GestureTrace.w(component, "dragCancel off=${"%.1f".format(state.offsetX)}")
                }
                state.endDrag(0f)
                isEdgeDrag = false
            },
            onHorizontalDrag = { change, dragAmount ->
                // 只有在边缘拖动时才消费事件
                if (!isEdgeDrag) return@detectHorizontalDragGestures
                change.consume()
                velocityTracker.addPosition(change.uptimeMillis, change.position)
                if (GestureTrace.isEnabled()) {
                    val now = change.uptimeMillis
                    if (now - lastLogUptimeMillis >= 120) {
                        lastLogUptimeMillis = now
                        GestureTrace.d(
                            component,
                            "drag dX=${"%.1f".format(dragAmount)} pos=(${"%.1f".format(change.position.x)},${"%.1f".format(change.position.y)}) off=${"%.1f".format(state.offsetX)}"
                        )
                    }
                }
                // 只响应向右滑动
                if (dragAmount > 0 || state.offsetX > 0) {
                    state.drag(dragAmount)
                }
            }
        )
    }
}

/**
 * 侧滑栏拖拽手势修饰符
 */
private fun Modifier.drawerDragGesture(state: SwipeableDrawerState): Modifier = this.pointerInput(state) {
    val velocityTracker = VelocityTracker()
    val component = "DrawerPanel"
    var lastLogUptimeMillis = 0L

    detectHorizontalDragGestures(
        onDragStart = {
            velocityTracker.resetTracking()
            if (GestureTrace.isEnabled()) {
                GestureTrace.w(component, "dragStart off=${"%.1f".format(state.offsetX)}")
            }
            state.startDrag()
        },
        onDragEnd = {
            val velocity = velocityTracker.calculateVelocity().x
            if (GestureTrace.isEnabled()) {
                GestureTrace.w(component, "dragEnd vX=${"%.1f".format(velocity)} off=${"%.1f".format(state.offsetX)}")
            }
            state.endDrag(velocity)
        },
        onDragCancel = {
            if (GestureTrace.isEnabled()) {
                GestureTrace.w(component, "dragCancel off=${"%.1f".format(state.offsetX)}")
            }
            state.endDrag(0f)
        },
        onHorizontalDrag = { change, dragAmount ->
            change.consume()
            velocityTracker.addPosition(
                change.uptimeMillis,
                change.position
            )
            if (GestureTrace.isEnabled()) {
                val now = change.uptimeMillis
                if (now - lastLogUptimeMillis >= 120) {
                    lastLogUptimeMillis = now
                    GestureTrace.d(
                        component,
                        "drag dX=${"%.1f".format(dragAmount)} pos=(${"%.1f".format(change.position.x)},${"%.1f".format(change.position.y)}) off=${"%.1f".format(state.offsetX)}"
                    )
                }
            }
            state.drag(dragAmount)
        }
    )
}
