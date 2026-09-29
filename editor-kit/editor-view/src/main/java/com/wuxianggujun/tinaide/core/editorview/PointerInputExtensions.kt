package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange

internal suspend fun AwaitPointerEventScope.awaitFirstPointerDown(
    pass: PointerEventPass = PointerEventPass.Main
): PointerInputChange {
    while (true) {
        val event = awaitPointerEvent(pass)
        val down = event.changes.firstOrNull { change ->
            change.pressed && !change.previousPressed
        }
        if (down != null) {
            return down
        }
    }
}
