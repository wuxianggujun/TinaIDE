package com.wuxianggujun.tinaide.ui.runtime

import android.app.Application
import android.os.Bundle
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CancelledSdlActivityTest {
    @Test
    fun `cancelled SDL3 activity never loads libraries or invokes native lifecycle`() {
        val controller = Robolectric.buildActivity(CancelledSdl3::class.java).create()
        // Deliberately simulate callbacks even after finish: none may enter JNI.
        controller.start().resume()
        controller.get().onWindowFocusChanged(true)
        controller.get().onSystemUiVisibilityChange(0)
        controller.get().onRequestPermissionsResult(1, emptyArray(), intArrayOf(0))
        controller.get().deliverActivityResult()
        controller.pause().stop().destroy()
        assertThat(controller.get().loadCount).isEqualTo(0)
    }

    @Test
    fun `cancelled SDL2 activity never loads libraries or invokes native lifecycle`() {
        val controller = Robolectric.buildActivity(CancelledSdl2::class.java).create()
        controller.start().resume()
        controller.get().onWindowFocusChanged(true)
        controller.get().onSystemUiVisibilityChange(0)
        controller.get().onRequestPermissionsResult(1, emptyArray(), intArrayOf(0))
        controller.pause().stop().destroy()
        assertThat(controller.get().loadCount).isEqualTo(0)
    }

    class CancelledSdl3 : org.libsdl.app.SDLActivity() {
        var loadCount = 0
        fun deliverActivityResult() = onActivityResult(1, RESULT_OK, null)
        override fun onCreate(savedInstanceState: Bundle?) {
            finish()
            super.onCreate(savedInstanceState)
        }

        override fun loadLibraries() {
            loadCount++
            error("A cancelled Activity must not load SDL")
        }
    }

    class CancelledSdl2 : org.libsdl2.app.SDLActivity() {
        var loadCount = 0
        override fun onCreate(savedInstanceState: Bundle?) {
            finish()
            super.onCreate(savedInstanceState)
        }

        override fun loadLibraries() {
            loadCount++
            error("A cancelled Activity must not load SDL2")
        }
    }
}
