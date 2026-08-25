package com.wuxianggujun.tinaide.ui

import android.annotation.TargetApi
import android.os.Build
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity

/**
 * Shared Activity base for TinaIDE's Compose screens.
 *
 * Compose UI 1.12.0-alpha03 reinterprets Android 14+ touchpad two-finger swipes as an unpressed
 * mouse pan. Existing Compose scrollables do not consume that stream, so restore the earlier touch
 * stream by clearing only the touchpad-scroll classification before dispatch.
 */
abstract class TinaComponentActivity : ComponentActivity() {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val compatibleEvent = createCompatibleCopy(event)
            ?: return super.dispatchTouchEvent(event)
        return try {
            super.dispatchTouchEvent(compatibleEvent)
        } finally {
            compatibleEvent.recycle()
        }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val compatibleEvent = createCompatibleCopy(event)
            ?: return super.dispatchGenericMotionEvent(event)
        return try {
            super.dispatchGenericMotionEvent(compatibleEvent)
        } finally {
            compatibleEvent.recycle()
        }
    }

    private fun createCompatibleCopy(event: MotionEvent): MotionEvent? =
        TouchpadScrollMotionEventCompat.createCompatibleCopy(
            event = event,
            displayId = window.decorView.display?.displayId ?: Display.DEFAULT_DISPLAY,
        )
}

internal object TouchpadScrollMotionEventCompat {
    fun shouldNormalize(
        sdkInt: Int,
        classification: Int,
        source: Int,
    ): Boolean =
        sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            classification == MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE &&
            (
                source.isFrom(InputDevice.SOURCE_MOUSE) ||
                    source.isFrom(InputDevice.SOURCE_TOUCHPAD)
            )

    fun createCompatibleCopy(
        event: MotionEvent,
        displayId: Int,
    ): MotionEvent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return Api34Impl.createCompatibleCopy(event, displayId)
    }

    @TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private object Api34Impl {
        fun createCompatibleCopy(
            event: MotionEvent,
            displayId: Int,
        ): MotionEvent? {
            if (!shouldNormalize(Build.VERSION.SDK_INT, event.classification, event.source)) {
                return null
            }

            val pointerCount = event.pointerCount
            if (pointerCount == 0) return null
            val pointerProperties = Array(pointerCount) { pointerIndex ->
                MotionEvent.PointerProperties().also { properties ->
                    event.getPointerProperties(pointerIndex, properties)
                }
            }
            val pointerCoords = Array(pointerCount) { MotionEvent.PointerCoords() }
            val hasHistory = event.historySize > 0 &&
                (event.actionMasked == MotionEvent.ACTION_MOVE ||
                    event.actionMasked == MotionEvent.ACTION_HOVER_MOVE)
            val rawOffsetX = event.getRawX(0) - event.getX(0)
            val rawOffsetY = event.getRawY(0) - event.getY(0)

            fillPointerCoords(
                source = event,
                destination = pointerCoords,
                historyPosition = if (hasHistory) 0 else null,
                rawOffsetX = rawOffsetX,
                rawOffsetY = rawOffsetY,
            )

            val compatibleEvent = MotionEvent.obtain(
                event.downTime,
                if (hasHistory) event.getHistoricalEventTime(0) else event.eventTime,
                event.action,
                pointerCount,
                pointerProperties,
                pointerCoords,
                event.metaState,
                event.buttonState,
                event.xPrecision,
                event.yPrecision,
                event.deviceId,
                event.edgeFlags,
                event.source,
                displayId,
                event.flags,
                MotionEvent.CLASSIFICATION_NONE,
            ) ?: return null

            compatibleEvent.offsetLocation(-rawOffsetX, -rawOffsetY)
            if (hasHistory) {
                for (historyPosition in 1 until event.historySize) {
                    fillPointerCoords(
                        source = event,
                        destination = pointerCoords,
                        historyPosition = historyPosition,
                        rawOffsetX = rawOffsetX,
                        rawOffsetY = rawOffsetY,
                    )
                    compatibleEvent.addBatch(
                        event.getHistoricalEventTime(historyPosition),
                        pointerCoords,
                        event.metaState,
                    )
                }

                fillPointerCoords(
                    source = event,
                    destination = pointerCoords,
                    historyPosition = null,
                    rawOffsetX = rawOffsetX,
                    rawOffsetY = rawOffsetY,
                )
                compatibleEvent.addBatch(event.eventTime, pointerCoords, event.metaState)
            }

            return compatibleEvent
        }

        private fun fillPointerCoords(
            source: MotionEvent,
            destination: Array<MotionEvent.PointerCoords>,
            historyPosition: Int?,
            rawOffsetX: Float,
            rawOffsetY: Float,
        ) {
            destination.forEachIndexed { pointerIndex, coords ->
                if (historyPosition == null) {
                    source.getPointerCoords(pointerIndex, coords)
                } else {
                    source.getHistoricalPointerCoords(pointerIndex, historyPosition, coords)
                }
                coords.x += rawOffsetX
                coords.y += rawOffsetY
            }
        }
    }

    private fun Int.isFrom(inputSource: Int): Boolean = (this and inputSource) == inputSource
}
