package com.wuxianggujun.tinaide.core.debug

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.proot.InteractiveProcess
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

class LldbDebuggerTest {
    @Test fun utf8SurvivesSingleByteReadsAndOrdinaryLldbTextIsNotAPrompt() = runBlocking {
        val process = FakeProcess()
        val debugger = LldbDebugger("lldb") { _, _, _ -> process }
        try {
            debugger.start("main", workingDirectory = "/").getOrThrow()
            val output = debugger.sendCommand("unicode").getOrThrow()
            assertThat(output).isEqualTo("中文🙂 (lldb) is target output\n")
        } finally { debugger.close() }
    }

    @Test fun timeoutInvalidatesSessionAndSameInstanceCanRestart() = runBlocking {
        val processes = mutableListOf<FakeProcess>()
        val debugger = LldbDebugger("lldb") { _, _, _ -> FakeProcess().also { processes += it } }
        try {
            debugger.start("main", workingDirectory = "/").getOrThrow()
            assertThat(debugger.sendCommand("hang", 50).isFailure).isTrue()
            assertThat(processes.first().isRunning()).isFalse()
            assertThat(debugger.sendCommand("unicode").isFailure).isTrue()
            debugger.start("main", workingDirectory = "/").getOrThrow()
            assertThat(debugger.sendCommand("unicode").getOrThrow()).contains("中文🙂")
        } finally { debugger.close() }
    }

    @Test fun eofFailsPendingCommandImmediately() = runBlocking {
        val process = FakeProcess()
        val debugger = LldbDebugger("lldb") { _, _, _ -> process }
        try {
            debugger.start("main", workingDirectory = "/").getOrThrow()
            withTimeout(2_000) { assertThat(debugger.sendCommand("exit", 60_000).isFailure).isTrue() }
        } finally { debugger.close() }
    }

    @Test fun stopUnblocksInFlightCommandWithoutWaitingForItsTimeout() = runBlocking {
        val process = FakeProcess()
        val debugger = LldbDebugger("lldb") { _, _, _ -> process }
        try {
            debugger.start("main", workingDirectory = "/").getOrThrow()
            val pending = async { debugger.sendCommand("hang", 60_000) }
            withTimeout(2_000) {
                process.hanging.await()
                debugger.stop().getOrThrow()
                assertThat(pending.await().isFailure).isTrue()
            }
        } finally { debugger.close() }
    }

    private class ByteStream : InputStream() {
        private val bytes = LinkedBlockingQueue<Int>()
        fun offer(text: String) { text.toByteArray(Charsets.UTF_8).forEach { bytes.put(it.toInt() and 255) } }
        override fun read(): Int = bytes.take()
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val value = read()
            if (value < 0) return -1
            buffer[offset] = value.toByte()
            return 1
        }
        override fun close() { bytes.offer(-1) }
    }

    private class FakeProcess : InteractiveProcess {
        override val stdout = ByteStream().apply { offer("(lldb) ") }
        override val stderr = ByteStream()
        private var prompt = "(lldb) "
        @Volatile private var running = true
        val hanging = CompletableDeferred<Unit>()
        override val stdin: OutputStream = object : ByteArrayOutputStream() {
            override fun flush() {
                val command = toString("UTF-8").trimEnd('\n')
                reset()
                when {
                    command.startsWith("settings set prompt ") -> {
                        prompt = command.substringAfter("settings set prompt ").trim('"')
                        stdout.offer(prompt)
                    }
                    command == "unicode" -> stdout.offer("中文🙂 (lldb) is target output\n$prompt")
                    command == "hang" -> hanging.complete(Unit)
                    command == "exit" -> stdout.close()
                    else -> stdout.offer(prompt)
                }
            }
        }
        override fun isRunning() = running
        override fun waitFor(timeout: Long) = if (running) -1 else 0
        override fun destroy() { running = false; stdout.close(); stderr.close() }
    }
}
