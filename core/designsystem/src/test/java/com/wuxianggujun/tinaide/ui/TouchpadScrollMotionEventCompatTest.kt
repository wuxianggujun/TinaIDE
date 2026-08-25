package com.wuxianggujun.tinaide.ui

import android.os.Build
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class TouchpadScrollMotionEventCompatTest {
    @Test
    fun `normalizes Android 14 touchpad two finger swipe`() {
        assertThat(
            TouchpadScrollMotionEventCompat.shouldNormalize(
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                classification = MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE,
                source = InputDevice.SOURCE_TOUCHPAD,
            )
        ).isTrue()
    }

    @Test
    fun `normalizes mouse sourced touchpad two finger swipe`() {
        assertThat(
            TouchpadScrollMotionEventCompat.shouldNormalize(
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                classification = MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE,
                source = InputDevice.SOURCE_MOUSE,
            )
        ).isTrue()
    }

    @Test
    fun `does not normalize before Android 14`() {
        assertThat(
            TouchpadScrollMotionEventCompat.shouldNormalize(
                sdkInt = Build.VERSION_CODES.TIRAMISU,
                classification = MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE,
                source = InputDevice.SOURCE_TOUCHPAD,
            )
        ).isFalse()
    }

    @Test
    fun `does not normalize unrelated motion events`() {
        assertThat(
            TouchpadScrollMotionEventCompat.shouldNormalize(
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                classification = MotionEvent.CLASSIFICATION_NONE,
                source = InputDevice.SOURCE_TOUCHPAD,
            )
        ).isFalse()
        assertThat(
            TouchpadScrollMotionEventCompat.shouldNormalize(
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                classification = MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE,
                source = InputDevice.SOURCE_TOUCHSCREEN,
            )
        ).isFalse()
    }

    @Test
    fun `compatible copy preserves event data and clears only classification`() {
        val properties = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 7
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                x = 120.5f
                y = 240.25f
                pressure = 0.75f
                setAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE, 18f)
            }
        )
        val original = checkNotNull(
            MotionEvent.obtain(
                100L,
                120L,
                MotionEvent.ACTION_MOVE,
                1,
                properties,
                coords,
                MotionEvent.META_SHIFT_ON,
                0,
                0.5f,
                0.75f,
                42,
                MotionEvent.EDGE_TOP,
                InputDevice.SOURCE_TOUCHPAD,
                0,
                MotionEvent.FLAG_WINDOW_IS_OBSCURED,
                MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE,
            )
        )

        val compatible = checkNotNull(
            TouchpadScrollMotionEventCompat.createCompatibleCopy(
                event = original,
                displayId = Display.DEFAULT_DISPLAY,
            )
        )
        try {
            assertThat(compatible.classification).isEqualTo(MotionEvent.CLASSIFICATION_NONE)
            assertThat(compatible.action).isEqualTo(original.action)
            assertThat(compatible.downTime).isEqualTo(original.downTime)
            assertThat(compatible.eventTime).isEqualTo(original.eventTime)
            assertThat(compatible.source).isEqualTo(original.source)
            assertThat(compatible.deviceId).isEqualTo(original.deviceId)
            assertThat(compatible.flags).isEqualTo(original.flags)
            assertThat(compatible.edgeFlags).isEqualTo(original.edgeFlags)
            assertThat(compatible.metaState).isEqualTo(original.metaState)
            assertThat(compatible.buttonState).isEqualTo(original.buttonState)
            assertThat(compatible.xPrecision).isEqualTo(original.xPrecision)
            assertThat(compatible.yPrecision).isEqualTo(original.yPrecision)
            assertThat(compatible.pointerCount).isEqualTo(original.pointerCount)
            assertThat(compatible.getPointerId(0)).isEqualTo(original.getPointerId(0))
            assertThat(compatible.getToolType(0)).isEqualTo(original.getToolType(0))
            assertThat(compatible.getX(0)).isEqualTo(original.getX(0))
            assertThat(compatible.getY(0)).isEqualTo(original.getY(0))
            assertThat(compatible.getRawX(0)).isEqualTo(original.getRawX(0))
            assertThat(compatible.getRawY(0)).isEqualTo(original.getRawY(0))
            assertThat(compatible.getPressure(0)).isEqualTo(original.getPressure(0))
            assertThat(
                compatible.getAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE)
            ).isEqualTo(original.getAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE))
        } finally {
            compatible.recycle()
            original.recycle()
        }
    }
}
