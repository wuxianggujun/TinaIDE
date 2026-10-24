package com.wuxianggujun.tinaide.core.debug

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.proot.PRootEnvironment
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DebugSessionServiceTest {
    @Test
    fun oldStepOutFailureCannotRestoreStateAfterRestart() = runBlocking {
        val first = Dialogue()
        val second = Dialogue()
        val entered = CompletableDeferred<Unit>()
        val result = CompletableDeferred<Result<String>>()
        coEvery { first.debugger.sendCommand("finish", any()) } coAnswers {
            entered.complete(Unit)
            result.await()
        }
        val dialogues = ArrayDeque(listOf(first, second))
        val service = service { dialogues.removeFirst().debugger }
        try {
            service.startSession("/main").getOrThrow()
            awaitPaused(service)
            service.stepOut()
            withTimeout(5_000) { entered.await() }
            service.stopSession()
            val newId = service.startSession("/main").getOrThrow()
            awaitPaused(service)
            result.complete(Result.failure(IOException("old process closed")))
            delay(100)
            assertThat((service.state.value as DebugState.Paused).sessionId).isEqualTo(newId)
        } finally {
            result.complete(Result.failure(IOException("test cleanup")))
            service.close()
        }
    }

    @Test
    fun oldVariableRequestCannotPublishIntoNewSession() = runBlocking {
        val first = Dialogue()
        val second = Dialogue()
        val entered = CompletableDeferred<Unit>()
        val selection = CompletableDeferred<Result<String>>()
        coEvery { first.debugger.sendCommand("frame select 7", any()) } coAnswers {
            entered.complete(Unit)
            selection.await()
        }
        val dialogues = ArrayDeque(listOf(first, second))
        val service = service { dialogues.removeFirst().debugger }
        try {
            service.startSession("/main").getOrThrow()
            awaitPaused(service)
            service.requestVariables(7)
            withTimeout(5_000) { entered.await() }
            service.stopSession()
            service.startSession("/main").getOrThrow()
            awaitPaused(service)
            val newVariables = service.variables.value
            coEvery { first.debugger.sendCommand("frame variable", any()) } returns Result.success("(int) obsolete = 99")
            selection.complete(Result.success(""))
            delay(100)
            assertThat(service.variables.value).isEqualTo(newVariables)
        } finally {
            selection.complete(Result.failure(IOException("test cleanup")))
            service.close()
        }
    }

    private fun service(factory: () -> LldbDebugger) = DebugSessionService(
        RuntimeEnvironment.getApplication(), mockk<PRootEnvironment>(relaxed = true), BreakpointStore(),
        debuggerFactory = { _, _ -> factory() },
    )

    private suspend fun awaitPaused(service: DebugSessionService) = withTimeout(5_000) {
        service.state.first { it is DebugState.Paused }
        service.variables.first { it.isNotEmpty() }
    }

    private class Dialogue {
        private val output = MutableSharedFlow<LldbDebugger.OutputChunk>(extraBufferCapacity = 16)
        val debugger = mockk<LldbDebugger>()
        init {
            every { debugger.output } returns output
            every { debugger.failure } returns MutableStateFlow(null)
            every { debugger.close() } returns Unit
            coEvery { debugger.start(any(), any(), any(), any()) } returns Result.success(Unit)
            coEvery { debugger.sendCommand(any(), any()) } coAnswers {
                when (firstArg<String>()) {
                    "run" -> {
                        output.emit(LldbDebugger.OutputChunk("Process 42 stopped\n", false))
                        Result.success("")
                    }
                    "frame variable" -> Result.success("(int) current = 1")
                    else -> Result.success("")
                }
            }
        }
    }
}
