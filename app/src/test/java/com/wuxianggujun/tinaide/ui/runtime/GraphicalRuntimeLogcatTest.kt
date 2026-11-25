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
        val output = "----- beginning of main\n\n" + (1..130).joinToString("\n") { "I/TINA_USER_OUTPUT(123): 第${it}行 😀" }
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
        assertThat(command).contains("ViewRootImpl:S")
        assertThat(process.destroyed).isTrue()
    }

    @Test
    fun levelsAndUnprefixedContinuationLinesArePreserved() = runTest(dispatcher, timeout = 10.seconds) {
        val process = FakeProcess(ByteArrayInputStream("W/SDL(123): warning\nE/TINA_USER_OUTPUT(123): error\nat frame.cpp:17\n".toByteArray(Charsets.UTF_8)))
        val entries = mutableListOf<GraphicalRuntimeLogEntry>()
        captureGraphicalRuntimeLogcat("failure", append = { entries.addAll(it) }, startProcess = { process })
        assertThat(entries).containsExactly(
            GraphicalRuntimeLogEntry('W', "warning"), GraphicalRuntimeLogEntry('E', "error"), GraphicalRuntimeLogEntry('E', "at frame.cpp:17"),
        ).inOrder()
    }

    @Test
    fun allProcessLogsExposeTheTagAndKeepCustomMessagesAndJavaStackTraces() = runTest(dispatcher, timeout = 10.seconds) {
        val output = "V/TINA_SDL_HOST( 123): onPause()\n" +
            "D/ViewRootImpl(123): window changed\n" +
            "I/MyGame(native)(123): 用户日志 😀 contains ): text\n" +
            "E/AndroidRuntime(123): FATAL EXCEPTION: main\n" +
            "E/AndroidRuntime(123):     at example.Game.run(Game.java:7)\n"
        val process = FakeProcess(ByteArrayInputStream(output.toByteArray(Charsets.UTF_8)))
        val entries = mutableListOf<GraphicalRuntimeLogEntry>()
        var command = emptyList<String>()
        captureGraphicalRuntimeLogcat("failure", append = { entries.addAll(it) }, showAllProcessLogs = true,
            startProcess = { command = it; process })
        assertThat(command).contains("*:V")
        assertThat(command).contains("--pid=${android.os.Process.myPid()}")
        assertThat(entries).containsExactly(
            GraphicalRuntimeLogEntry('V', "[TINA_SDL_HOST] onPause()"),
            GraphicalRuntimeLogEntry('D', "[ViewRootImpl] window changed"),
            GraphicalRuntimeLogEntry('I', "[MyGame(native)] 用户日志 😀 contains ): text"),
            GraphicalRuntimeLogEntry('E', "[AndroidRuntime] FATAL EXCEPTION: main"),
            GraphicalRuntimeLogEntry('E', "[AndroidRuntime]     at example.Game.run(Game.java:7)"),
        ).inOrder()
    }

    @Test
    fun programViewKeepsFatalFailuresAndDoesNotStripHeaderLikeTextFromContinuations() = runTest(dispatcher, timeout = 10.seconds) {
        val output = "E/AndroidRuntime(123): java.lang.IllegalStateException: boom\n" +
            "F/libc    ( 123): Fatal signal 11\n" +
            "E/TINA_USER_OUTPUT(123): shader failed\n" +
            "Debug context (shader): fragment\n"
        val process = FakeProcess(ByteArrayInputStream(output.toByteArray(Charsets.UTF_8)))
        val entries = mutableListOf<GraphicalRuntimeLogEntry>()
        captureGraphicalRuntimeLogcat("failure", append = { entries.addAll(it) }, startProcess = { process })
        assertThat(entries).containsExactly(
            GraphicalRuntimeLogEntry('E', "java.lang.IllegalStateException: boom"),
            GraphicalRuntimeLogEntry('F', "Fatal signal 11"),
            GraphicalRuntimeLogEntry('E', "shader failed"),
            GraphicalRuntimeLogEntry('E', "Debug context (shader): fragment"),
        ).inOrder()
    }

    @Test
    fun noisyNativeActivityWindowsCannotDisplaceRaylibOrCustomProgramOutput() = runTest(dispatcher, timeout = 10.seconds) {
        val output = "D/MyGraphicsEngine(123): first user line\n" +
            (1..600).joinToString("\n", postfix = "\n") { "D/VRI[ExternalNativeActivity]@abc(123): frame $it" } +
            "    at internal.Window.dispatch(Window.java:7)\n" +
            "V/TINA_SDL_HOST(123): onResume()\n" +
            "V/SDL(123): user verbose detail\n" +
            "I/raylib(123): last user line 😀\n"
        val process = FakeProcess(ByteArrayInputStream(output.toByteArray(Charsets.UTF_8)))
        val entries = mutableListOf<GraphicalRuntimeLogEntry>()
        captureGraphicalRuntimeLogcat("failure", append = { entries.addAll(it) }, startProcess = { process })
        assertThat(entries).containsExactly(
            GraphicalRuntimeLogEntry('D', "first user line"),
            GraphicalRuntimeLogEntry('V', "user verbose detail"),
            GraphicalRuntimeLogEntry('I', "last user line 😀"),
        ).inOrder()
    }

    @Test
    fun vendorTouchDiagnosticsCannotDisplaceProgramOutputOrHideUserMessagesWithTheSameMethodName() = runTest(dispatcher, timeout = 10.seconds) {
        val output = "I/TINA_USER_OUTPUT(123): first user line\n" +
            (1..600).joinToString("\n", postfix = "\n") {
                "I/DynamicFramerate [AnimationSpeedAware]( 123): onEventHandled: info: InputEventInfo: mAction = 0, event = $it"
            } +
            "    touch diagnostic continuation\n" +
            "I/SDL(123): user onEventHandled: tap\n" +
            "I/raylib(123): user onEventHandled: click\n" +
            "I/MyEngine(123): last user line 中文😀\n" +
            "W/DynamicFramerate [AnimationSpeedAware](123): frame-rate failure\n"
        val entries = mutableListOf<GraphicalRuntimeLogEntry>()
        captureGraphicalRuntimeLogcat("failure", append = { entries.addAll(it) },
            startProcess = { FakeProcess(ByteArrayInputStream(output.toByteArray(Charsets.UTF_8))) })
        assertThat(entries).containsExactly(
            GraphicalRuntimeLogEntry('I', "first user line"),
            GraphicalRuntimeLogEntry('I', "user onEventHandled: tap"),
            GraphicalRuntimeLogEntry('I', "user onEventHandled: click"),
            GraphicalRuntimeLogEntry('I', "last user line 中文😀"),
            GraphicalRuntimeLogEntry('W', "frame-rate failure"),
        ).inOrder()

        entries.clear()
        captureGraphicalRuntimeLogcat("failure", append = { entries.addAll(it) }, showAllProcessLogs = true,
            startProcess = { FakeProcess(ByteArrayInputStream(output.toByteArray(Charsets.UTF_8))) })
        assertThat(entries).hasSize(606)
        assertThat(entries[1].message).startsWith("[DynamicFramerate [AnimationSpeedAware]] onEventHandled:")
        assertThat(entries[601].message).contains("touch diagnostic continuation")
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
