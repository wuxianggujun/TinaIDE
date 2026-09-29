package com.wuxianggujun.tinaide.core.editorview

import android.os.SystemClock

internal class EditorTransformFocusTracker(
    private val ui: TinaEditorUiState,
    private val touchDiagnostics: EditorTouchDiagnostics
) {
    fun onFocusSnapshot(snapshot: TransformGestureFocusSnapshot) {
        ui.transformGestureFocusPointerCount = snapshot.pointerCount
        ui.transformGestureFocusStablePointerCount = snapshot.stablePointerCount
        ui.transformGestureFocusBasis = snapshot.basis

        val focus = snapshot.focus
        if (focus != null) {
            ui.transformGestureFocus = focus
            ui.transformGestureFocusSeq += 1
            ui.transformGestureFocusUpdatedAtMs = SystemClock.uptimeMillis()
        }

        if (focus != null) {
            touchDiagnostics.logThrottled(
                category = EditorTouchLogCategory.FOCUS,
                throttleKey = "focus-update",
                minIntervalMs = 120L
            ) {
                val now = SystemClock.uptimeMillis()
                "focusUpdate t=$now seq=${ui.transformGestureFocusSeq} " +
                    "focus=(${focus.x.format1()},${focus.y.format1()}) " +
                    "p=${snapshot.pointerCount} stable=${snapshot.stablePointerCount} basis=${snapshot.basis.label()}"
            }
        } else if (snapshot.pointerCount >= 2) {
            touchDiagnostics.logThrottled(
                category = EditorTouchLogCategory.FOCUS,
                throttleKey = "focus-hold",
                minIntervalMs = 120L
            ) {
                val now = SystemClock.uptimeMillis()
                "focusHold t=$now seq=${ui.transformGestureFocusSeq} " +
                    "reason=insufficientStablePointers p=${snapshot.pointerCount} stable=${snapshot.stablePointerCount}"
            }
        }
    }
}

private fun Float.format1(): String = String.format("%.1f", this)

private fun TransformGestureFocusBasis.label(): String = when (this) {
    TransformGestureFocusBasis.PREVIOUS_STABLE -> "previousStable"
    TransformGestureFocusBasis.NONE -> "-"
}
