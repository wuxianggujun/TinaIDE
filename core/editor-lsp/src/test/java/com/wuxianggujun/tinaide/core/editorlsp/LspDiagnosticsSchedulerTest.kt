package com.wuxianggujun.tinaide.core.editorlsp

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LspDiagnosticsSchedulerTest {
    @Test fun staleNonCooperativeRequestCannotPublishAfterReplacement() = runTest {
        val scheduler = LspDiagnosticsScheduler(this, Any(), StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        val published = mutableListOf<String>()
        scheduler.schedule("tab", {
            withContext(NonCancellable) { gate.await() }
            emptyList()
        }, { published += "old" })
        runCurrent()
        scheduler.schedule("tab", { emptyList() }, { published += "new" })
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertThat(published).containsExactly("new")
    }

    @Test fun closingTabBeforeQueuedWorkRunsPreventsPublication() = runTest {
        val scheduler = LspDiagnosticsScheduler(this, Any(), StandardTestDispatcher(testScheduler))
        var published = false
        scheduler.schedule("tab", { emptyList() }, { published = true })
        scheduler.remove("tab")?.cancel()
        runCurrent()
        assertThat(published).isFalse()
        scheduler.schedule("tab", { emptyList() }, { published = true })
        scheduler.cancelAll()
        runCurrent()
        assertThat(published).isFalse()
    }
}
