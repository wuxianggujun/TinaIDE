package com.wuxianggujun.tinaide.editor.session

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AutoSaveSchedulerTest {

    @Test
    fun schedule_shouldRetryWhileSessionRemainsDirtyAfterSave() = runTest {
        val file = Files.createTempFile("auto-save-scheduler", ".txt").toFile()
        val session = DocumentSession(
            context = RuntimeEnvironment.getApplication(),
            tabId = "tab-id",
            file = file,
            coroutineScope = this
        )
        var calls = 0
        val scheduler = AutoSaveScheduler(
            scope = this,
            intervalProvider = { 100L }
        ) {
            calls++
            calls < 2
        }

        try {
            scheduler.schedule(session)

            advanceTimeBy(100L)
            runCurrent()
            assertThat(calls).isEqualTo(1)

            advanceTimeBy(100L)
            runCurrent()
            assertThat(calls).isEqualTo(2)
        } finally {
            scheduler.cancelAll()
            session.stopFileWatcher()
            file.delete()
        }
    }
}
