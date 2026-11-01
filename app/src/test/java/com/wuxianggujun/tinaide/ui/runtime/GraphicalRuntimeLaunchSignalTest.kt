package com.wuxianggujun.tinaide.ui.runtime

import android.app.Application
import android.content.Intent
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class GraphicalRuntimeLaunchSignalTest {
    @Test
    fun `only the first Activity can claim a launch`() = runTest {
        val signal = GraphicalRuntimeLaunchSignal()
        val intent = Intent().also(signal::attachTo)

        assertThat(GraphicalRuntimeLaunchSignal.claim(intent)).isTrue()
        assertThat(GraphicalRuntimeLaunchSignal.claim(Intent(intent))).isFalse()
        assertThat(signal.awaitStarted()).isTrue()
    }

    @Test
    fun `timeout revokes an intent still queued in Android`() = runTest {
        val signal = GraphicalRuntimeLaunchSignal()
        val intent = Intent().also(signal::attachTo)

        assertThat(signal.awaitStarted()).isFalse()
        assertThat(GraphicalRuntimeLaunchSignal.claim(intent)).isFalse()
    }

    @Test
    fun `cancelling startup prevents a late Activity from loading an engine`() = runTest {
        val signal = GraphicalRuntimeLaunchSignal()
        val intent = Intent().also(signal::attachTo)
        val waiting = launch { signal.awaitStarted() }
        testScheduler.runCurrent()
        waiting.cancel()
        waiting.join()

        assertThat(GraphicalRuntimeLaunchSignal.claim(intent)).isFalse()
    }

    @Test
    fun `closing a failed launch does not revoke a later launch`() = runTest {
        val previous = GraphicalRuntimeLaunchSignal()
        val oldIntent = Intent().also(previous::attachTo)
        previous.cancelPending()
        val next = GraphicalRuntimeLaunchSignal()
        val newIntent = Intent().also(next::attachTo)

        assertThat(GraphicalRuntimeLaunchSignal.claim(oldIntent)).isFalse()
        assertThat(GraphicalRuntimeLaunchSignal.claim(newIntent)).isTrue()
        assertThat(next.awaitStarted()).isTrue()
    }

    @Test
    fun `an uncoordinated launch cannot load native code`() {
        assertThat(GraphicalRuntimeLaunchSignal.claim(Intent())).isFalse()
    }
}
