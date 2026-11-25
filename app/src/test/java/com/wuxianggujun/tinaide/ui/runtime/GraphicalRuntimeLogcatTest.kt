package com.wuxianggujun.tinaide.ui.runtime

import android.app.Application
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.time.Duration.Companion.seconds
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], manifest = Config.NONE)
class GraphicalRuntimeLogcatTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun installMainDispatcher() { Dispatchers.setMain(dispatcher) }
    @After fun resetMainDispatcher() { Dispatchers.resetMain() }

    @Test
    fun utf8OutputIsBatchedInOrderAndDeliveredOnTheOwnerThread() = runTest(dispatcher, timeout = 10.seconds) {
        val mainThread = Thread.currentThread()
        val output = "----- beginning of main\n\n" + (1..130).joinToString("\n") { "I/UserTag(123): 第${it}行 😀" }
        val process = FakeProcess(ByteArrayInputStream(output.toByteArray(Charsets.UTF_8)))
        val batches = mutableListOf<List<GraphicalRuntimeLogEntry>>()
        var command = emptyList<String>()
        captureGraphicalRuntimeLogcat("failure", append = {
            assertThat(Thread.currentThread()).isSameInstanceAs(mainThread)
            batches.add(it)
        }, startProcess = { command = it; process })
        assertThat(batches.flatten()).containsExactlyElementsIn((1..130).map { GraphicalRuntimeLogEntry('I', "第${it}行 😀") }).inOrder()
        assertThat(batches.all { it.size in 1..64 }).isTrue()
        assertThat(command).contains("--pid=${android.os.Process.myPid()}")
        assertThat(command).contains("*:V")
        assertThat(process.destroyed).isTrue()
    }

    @Test
    fun levelsAndUnprefixedContinuationLinesArePreserved() = runTest(dispatcher, timeout = 10.seconds) {
        val process = FakeProcess(ByteArrayInputStream("W/SDL(123): warning\nE/User(123): error\nat frame.cpp:17\n".toByteArray(Charsets.UTF_8)))
        val entries = mutableListOf<GraphicalRuntimeLogEntry>()
        captureGraphicalRuntimeLogcat("failure", append = { entries.addAll(it) }, startProcess = { process })
        assertThat(entries).containsExactly(
            GraphicalRuntimeLogEntry('W', "warning"), GraphicalRuntimeLogEntry('E', "error"), GraphicalRuntimeLogEntry('I', "at frame.cpp:17"),
        ).inOrder()
    }

    @Test
    fun startupFailureUsesTheSuppliedLocalizedMessage() = runTest(dispatcher, timeout = 10.seconds) {
        val entries = mutableListOf<GraphicalRuntimeLogEntry>()
        captureGraphicalRuntimeLogcat("本地化失败文案", append = { entries.addAll(it) }, startProcess = { throw IOException("fixture") })
        assertThat(entries).containsExactly(GraphicalRuntimeLogEntry('E', "本地化失败文案"))
    }

    @Test
    fun readFailureIsReportedAndStillReleasesTheProcess() = runTest(dispatcher, timeout = 10.seconds) {
        val process = FakeProcess(object : InputStream() { override fun read(): Int = throw IOException("fixture") })
        val entries = mutableListOf<GraphicalRuntimeLogEntry>()
        captureGraphicalRuntimeLogcat("read failed", append = { entries.addAll(it) }, startProcess = { process })
        assertThat(entries).containsExactly(GraphicalRuntimeLogEntry('E', "read failed"))
        assertThat(process.destroyed).isTrue()
    }

    @Test
    fun disposalUnblocksReadLineAndDoesNotAppendAFakeError() = runTest(dispatcher, timeout = 10.seconds) {
        val enteredRead = CompletableDeferred<Unit>()
        val released = CountDownLatch(1)
        val process = FakeProcess(object : InputStream() {
            override fun read(): Int { enteredRead.complete(Unit); released.await(); return -1 }
            override fun close() { released.countDown() }
        })
        val entries = mutableListOf<GraphicalRuntimeLogEntry>()
        val capture = launch { captureGraphicalRuntimeLogcat("failure", append = { entries.addAll(it) }, startProcess = { process }) }
        enteredRead.await()
        capture.cancelAndJoin()
        assertThat(process.destroyed).isTrue()
        assertThat(entries).isEmpty()
    }

    @Test
    fun cancellationDuringProcessCreationDoesNotLeakTheNewProcess() = runTest(dispatcher, timeout = 10.seconds) {
        val enteredStart = CompletableDeferred<Unit>()
        val allowStart = CountDownLatch(1)
        val process = FakeProcess(ByteArrayInputStream(byteArrayOf()))
        val capture = launch { captureGraphicalRuntimeLogcat("failure", append = {}, startProcess = {
            enteredStart.complete(Unit)
            allowStart.await()
            process
        }) }
        enteredStart.await()
        capture.cancel()
        allowStart.countDown()
        capture.join()
        assertThat(process.destroyed).isTrue()
    }

    private class FakeProcess(private val input: InputStream) : Process() {
        @Volatile var destroyed = false
        private val error = ByteArrayInputStream(byteArrayOf())
        private val output = ByteArrayOutputStream()
        override fun getInputStream() = input
        override fun getErrorStream() = error
        override fun getOutputStream() = output
        override fun waitFor() = 0
        override fun exitValue() = 0
        override fun destroy() { destroyed = true; input.close() }
    }
}
