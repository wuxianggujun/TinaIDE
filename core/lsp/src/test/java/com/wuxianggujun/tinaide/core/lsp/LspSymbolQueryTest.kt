package com.wuxianggujun.tinaide.core.lsp

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

class LspSymbolQueryTest {
    private val navigation = LspNavigationService()

    @Test
    fun emptyResponse_remainsSuccessfulEmptyResult() = runTest {
        assertThat(navigation.workspaceSymbol("run") { _, _ -> null }).isEmpty()
        assertThat(navigation.documentSymbols("file:///main.cpp") { _, _ -> emptyList() }).isEmpty()
    }

    @Test
    fun serverFailure_isNotConvertedToNoSymbols() = runTest {
        val failure = IOException("language server disconnected")
        val workspace = runCatching { navigation.workspaceSymbol("run") { _, _ -> throw failure } }
        val document = runCatching { navigation.documentSymbols("file:///main.cpp") { _, _ -> throw failure } }
        for (result in listOf(workspace, document)) {
            assertThat(result.exceptionOrNull()).isInstanceOf(IOException::class.java)
            assertThat(result.exceptionOrNull()).hasMessageThat().isEqualTo(failure.message)
        }
    }

    @Test
    fun cancellation_propagatesToTheCaller() = runTest {
        val cancelled = CancellationException("query replaced")
        val workspace = runCatching { navigation.workspaceSymbol("old") { _, _ -> throw cancelled } }
        val document = runCatching { navigation.documentSymbols("file:///old.cpp") { _, _ -> throw cancelled } }
        for (result in listOf(workspace, document)) {
            assertThat(result.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
            assertThat(result.exceptionOrNull()).hasMessageThat().isEqualTo(cancelled.message)
        }
    }
}
