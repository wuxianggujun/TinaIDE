package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import com.wuxianggujun.tinaide.core.textengine.Position
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class EditorMouseHoverTarget(
    val position: Position,
    val anchorInViewportPx: IntOffset
)

internal class EditorMouseHoverCoordinator(
    private val coroutineScope: CoroutineScope,
    private val hoverDelayMs: Long = DEFAULT_HOVER_DELAY_MS,
    private val resolveTarget: (Offset) -> EditorMouseHoverTarget?,
    private val hideCurrentHover: () -> Unit,
    private val requestHover: (EditorMouseHoverTarget) -> Unit,
    private val dismissHover: () -> Unit
) {
    private var pendingJob: Job? = null
    private var pendingTarget: EditorMouseHoverTarget? = null
    private var activeTarget: EditorMouseHoverTarget? = null

    fun onMove(position: Offset) {
        val target = resolveTarget(position)
        if (target == null) {
            cancelAndDismiss()
            return
        }

        if (isSameTextPosition(target, activeTarget) || isSameTextPosition(target, pendingTarget)) {
            return
        }

        if (activeTarget != null) {
            activeTarget = null
            hideCurrentHover()
        }

        pendingJob?.cancel()
        pendingTarget = target
        pendingJob = coroutineScope.launch {
            delay(hoverDelayMs)
            if (!isSameTextPosition(target, pendingTarget)) return@launch
            pendingTarget = null
            activeTarget = target
            requestHover(target)
        }
    }

    fun cancelAndDismiss() {
        val hadPendingOrActive = pendingTarget != null || activeTarget != null
        pendingJob?.cancel()
        pendingJob = null
        pendingTarget = null
        activeTarget = null
        if (hadPendingOrActive) {
            dismissHover()
        }
    }

    private fun isSameTextPosition(
        first: EditorMouseHoverTarget?,
        second: EditorMouseHoverTarget?
    ): Boolean = first != null && second != null && first.position == second.position

    private companion object {
        private const val DEFAULT_HOVER_DELAY_MS = 650L
    }
}
