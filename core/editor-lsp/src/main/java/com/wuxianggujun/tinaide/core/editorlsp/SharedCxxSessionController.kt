package com.wuxianggujun.tinaide.core.editorlsp

import com.wuxianggujun.tinaide.core.lsp.LspClientSession
import com.wuxianggujun.tinaide.core.lsp.LspConnectionProvider
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 共享 CXX（clangd）会话生命周期：复用、空闲关闭、dispose 抽取。
 *
 * 从 [LspEditorManager] 拆出，降低 attach/release 路径的状态耦合。
 */
internal class SharedCxxSessionController(
    private val scope: CoroutineScope,
    private val stateLock: Any,
    private val idleShutdownMs: Long,
    private val hasActiveCxxBindings: () -> Boolean,
    private val onIdleReleased: () -> Unit,
    private val createSession: (LspConnectionProvider, String, File) -> LspClientSession,
) {
    companion object {
        private const val TAG = "SharedCxxSession"
    }

    private val sessionMutex = Mutex()

    @Volatile
    private var sharedSession: LspClientSession? = null

    @Volatile
    private var shutdownJob: Job? = null

    fun currentSession(): LspClientSession? = sharedSession

    fun cancelPendingShutdown() {
        synchronized(stateLock) {
            shutdownJob?.cancel()
            shutdownJob = null
        }
    }

    /**
     * 在 mutex 内复用已连接会话，或创建新共享会话。
     */
    suspend fun obtainOrCreate(
        file: File,
        workspaceRoot: String,
        documentUri: String,
        languageId: String,
        initialText: String,
        initializationOptions: Any?,
        providerFactory: suspend () -> LspConnectionProvider,
        commitSessionIfCurrent: (
            session: LspClientSession,
            commit: () -> Unit,
        ) -> Boolean,
    ): LspClientSession = sessionMutex.withLock {
        val existing = synchronized(stateLock) { sharedSession }
        if (existing != null && existing.isConnected) {
            Timber.tag(TAG).d("reusing shared clangd for %s", file.name)
            withContext(Dispatchers.IO) {
                existing.activateDocumentIfCurrent(
                    documentUri = documentUri,
                    languageId = languageId,
                    initialText = initialText,
                    commitIfCurrent = { activate ->
                        commitSessionIfCurrent(existing, activate)
                    },
                ).getOrThrow()
            }
            return@withLock existing
        }

        Timber.tag(TAG).d("creating shared clangd session for %s", file.name)
        val provider = providerFactory()
        val session = createSession(provider, workspaceRoot, file)
        try {
            if (!commitSessionIfCurrent(session, {})) {
                throw CancellationException("Shared clangd attachment is no longer current")
            }
            withContext(Dispatchers.IO) {
                session.connect(
                    languageId = languageId,
                    initialText = initialText,
                    initializationOptions = initializationOptions,
                ).getOrThrow()
            }
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { session.close() }
            }
            throw error
        }
        synchronized(stateLock) { sharedSession = session }
        session
    }

    fun scheduleIdleShutdownIfNeeded() {
        val session = synchronized(stateLock) {
            if (hasActiveCxxBindings()) {
                shutdownJob?.cancel()
                shutdownJob = null
                return
            }
            val current = sharedSession ?: return
            shutdownJob?.cancel()
            current
        }

        val job = scope.launch {
            delay(idleShutdownMs)
            val sessionToClose = synchronized(stateLock) {
                if (hasActiveCxxBindings()) {
                    shutdownJob = null
                    null
                } else if (sharedSession === session) {
                    sharedSession = null
                    shutdownJob = null
                    session
                } else {
                    shutdownJob = null
                    null
                }
            } ?: return@launch

            runCatching {
                withContext(Dispatchers.IO) { sessionToClose.close() }
            }.onFailure { error ->
                Timber.tag(TAG).d(error, "shared clangd idle shutdown failed")
            }
            Timber.tag(TAG).i("shared clangd released after %dms idle", idleShutdownMs)
            onIdleReleased()
        }

        synchronized(stateLock) {
            if (sharedSession === session && !hasActiveCxxBindings()) {
                shutdownJob = job
            } else {
                job.cancel()
            }
        }
    }

    /** dispose 时取出并清空共享会话（调用方负责 close）。 */
    fun takeForDispose(): LspClientSession? {
        cancelPendingShutdown()
        return synchronized(stateLock) {
            sharedSession.also { sharedSession = null }
        }
    }
}
