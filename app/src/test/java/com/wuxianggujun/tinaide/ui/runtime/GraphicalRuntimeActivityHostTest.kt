package com.wuxianggujun.tinaide.ui.runtime

import android.app.Activity
import android.app.Application
import android.content.Intent
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Duration
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class GraphicalRuntimeActivityHostTest {
    private val activity = mockk<Activity>(relaxed = true)
    private val deadlines = mutableListOf<() -> Unit>()
    private var killed = 0
    private val host = GraphicalRuntimeActivityHost(
        activity,
        "GraphicalRuntimeHostTest",
        GraphicalRuntimeExitGuard(
            terminateProcess = { killed++ },
            schedule = { _, action -> deadlines += action },
        ),
    )

    @Test
    fun `Home and ordinary backgrounding never arm a shutdown deadline`() {
        host.onStart()
        host.onResume()
        host.onPause()
        host.onStop()
        host.onBeforeDestroy()
        host.onDestroy()

        assertThat(deadlines).isEmpty()
        assertThat(killed).isEqualTo(0)
    }

    @Test
    fun `native quit is requested only after the independent exit guard is armed`() {
        var nativeQuitCount = 0
        host.requestExit {
            assertThat(deadlines).hasSize(1)
            nativeQuitCount++
        }
        host.requestExit { nativeQuitCount++ }

        assertThat(nativeQuitCount).isEqualTo(1)
        verify(exactly = 0) { activity.finish() }
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(801))
        verify(exactly = 1) { activity.finish() }
        deadlines.single().invoke()
        assertThat(killed).isEqualTo(1)
    }

    @Test
    fun `finish arms deadline before native destroy and successful destroy reclaims immediately`() {
        every { activity.isFinishing } returns true
        host.onFinish()
        host.onBeforeDestroy()
        assertThat(deadlines).hasSize(1)
        assertThat(killed).isEqualTo(0)

        host.onDestroy()
        assertThat(killed).isEqualTo(1)
        deadlines.single().invoke()
        assertThat(killed).isEqualTo(1)
    }

    @Test
    fun `configuration change does not terminate a running engine`() {
        every { activity.isFinishing } returns true
        every { activity.isChangingConfigurations } returns true
        host.onBeforeDestroy()
        host.onDestroy()

        assertThat(deadlines).isEmpty()
        assertThat(killed).isEqualTo(0)
    }

    @Test
    fun `revoked Activity completes without killing a newer run in the same process`() {
        assertThat(host.onPreCreate(null, Intent())).isFalse()
        every { activity.isFinishing } returns true
        host.onFinish()
        host.onBeforeDestroy()
        host.onDestroy()

        verify(exactly = 1) { activity.finish() }
        assertThat(deadlines).isEmpty()
        assertThat(killed).isEqualTo(0)
    }
}
