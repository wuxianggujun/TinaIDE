package com.wuxianggujun.tinaide.core.editorlsp

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.lsp.LspClientSession
import com.wuxianggujun.tinaide.core.lsp.LspConnectionProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Test

class SharedCxxSessionControllerTest {

    @Test
    fun `rejected waiter cannot switch the shared clangd document`() = runBlocking {
        val session = mockk<LspClientSession>()
        val provider = mockk<LspConnectionProvider>()
        every { session.isConnected } returns true
        every {
            session.connect(
                languageId = "cpp",
                initialText = "int active;",
                initializationOptions = null,
            )
        } returns Result.success(Unit)
        var documentActivated = false
        every {
            session.activateDocumentIfCurrent(
                documentUri = "file:///workspace/stale.cpp",
                languageId = "cpp",
                initialText = "int stale;",
                commitIfCurrent = any(),
            )
        } answers {
            val commitIfCurrent = arg<((() -> Unit) -> Boolean)>(3)
            val committed = commitIfCurrent { documentActivated = true }
            if (committed) {
                Result.success(Unit)
            } else {
                Result.failure(CancellationException("stale attachment"))
            }
        }
        val controller = SharedCxxSessionController(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            stateLock = Any(),
            idleShutdownMs = 1_000L,
            hasActiveCxxBindings = { true },
            onIdleReleased = {},
            createSession = { _, _, _ -> session },
        )

        controller.obtainOrCreate(
            file = File("active.cpp"),
            workspaceRoot = ".",
            documentUri = "file:///workspace/active.cpp",
            languageId = "cpp",
            initialText = "int active;",
            initializationOptions = null,
            providerFactory = { provider },
            commitSessionIfCurrent = { _, commit ->
                commit()
                true
            },
        )

        val failure = runCatching {
            controller.obtainOrCreate(
                file = File("stale.cpp"),
                workspaceRoot = ".",
                documentUri = "file:///workspace/stale.cpp",
                languageId = "cpp",
                initialText = "int stale;",
                initializationOptions = null,
                providerFactory = { provider },
                commitSessionIfCurrent = { _, _ -> false },
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(documentActivated).isFalse()
        verify(exactly = 1) {
            session.activateDocumentIfCurrent(
                documentUri = "file:///workspace/stale.cpp",
                languageId = "cpp",
                initialText = "int stale;",
                commitIfCurrent = any(),
            )
        }
    }
}
