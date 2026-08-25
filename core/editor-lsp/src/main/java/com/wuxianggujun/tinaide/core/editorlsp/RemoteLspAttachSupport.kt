package com.wuxianggujun.tinaide.core.editorlsp

import com.wuxianggujun.tinaide.core.lsp.ConnectionEvent
import com.wuxianggujun.tinaide.core.lsp.ConnectionState
import com.wuxianggujun.tinaide.core.lsp.ConnectionStateListener
import com.wuxianggujun.tinaide.core.lsp.ProjectSyncManager
import com.wuxianggujun.tinaide.core.lsp.RemoteLspConfigManager
import com.wuxianggujun.tinaide.core.lsp.RemoteLspConnectionProvider
import com.wuxianggujun.tinaide.core.lsp.RemoteLspConnectionState
import com.wuxianggujun.tinaide.core.lsp.RemoteLspSyncMethod
import com.wuxianggujun.tinaide.core.lsp.RemoteLspSyncMode
import java.io.File
import timber.log.Timber

/**
 * Remote LSP 连接与项目同步辅助逻辑，从 [LspEditorManager] 抽出。
 */
internal object RemoteLspAttachSupport {
    private const val TAG = "RemoteLspAttach"

    fun createProvider(host: String, port: Int, ext: String): RemoteLspConnectionProvider =
        RemoteLspConnectionProvider(
            host = host,
            port = port,
            secureTransport = RemoteLspConfigManager.config.secureTransport,
            authenticationToken = RemoteLspConfigManager.getAuthenticationToken(),
            autoReconnect = true,
            maxReconnectAttempts = 5,
        ).also { provider ->
            provider.addStateListener(object : ConnectionStateListener {
                override fun onStateChanged(state: ConnectionState) {
                    val mapped = when (state) {
                        ConnectionState.DISCONNECTED -> RemoteLspConnectionState.DISCONNECTED
                        ConnectionState.CONNECTING, ConnectionState.RECONNECTING ->
                            RemoteLspConnectionState.CONNECTING
                        ConnectionState.CONNECTED -> RemoteLspConnectionState.CONNECTED
                        ConnectionState.FAILED -> RemoteLspConnectionState.ERROR
                    }
                    RemoteLspConfigManager.updateConnectionState(mapped)
                }

                override fun onEvent(event: ConnectionEvent) {
                    when (event) {
                        is ConnectionEvent.Connected -> Timber.tag(TAG).i("Remote connected: %s", ext)
                        is ConnectionEvent.Disconnected -> Timber.tag(TAG).i("Remote disconnected: %s", ext)
                        is ConnectionEvent.Reconnecting ->
                            RemoteLspConfigManager.updateReconnectAttempt(event.attempt)
                        is ConnectionEvent.Error ->
                            RemoteLspConfigManager.updateConnectionState(
                                RemoteLspConnectionState.ERROR,
                                event.message,
                            )
                        is ConnectionEvent.LatencyUpdate ->
                            RemoteLspConfigManager.updateLatency(event.latencyMs)
                    }
                }
            })
        }

    suspend fun resolveEffectiveSyncMode(configured: RemoteLspSyncMode, projectRoot: File): RemoteLspSyncMode =
        when (configured) {
            RemoteLspSyncMode.AUTO -> {
                val (mode, reason) = ProjectSyncManager.detectSyncMode(projectRoot)
                RemoteLspConfigManager.updateDetectedSyncMode(mode, reason)
                mode
            }
            else -> configured
        }

    suspend fun syncProjectIfNeeded(
        projectRoot: File,
        provider: RemoteLspConnectionProvider,
        syncMode: RemoteLspSyncMode,
        alreadySynced: Boolean,
    ): Boolean {
        val cfg = RemoteLspConfigManager.config
        if (syncMode != RemoteLspSyncMode.PROJECT ||
            cfg.syncMethod != RemoteLspSyncMethod.BUILTIN ||
            alreadySynced
        ) {
            return alreadySynced
        }
        val started = provider.startAsync()
        if (started.isFailure) {
            error(started.exceptionOrNull()?.message ?: "remote start failed")
        }
        val files = ProjectSyncManager.scanProject(projectRoot)
        return files.isEmpty() || provider.syncProject(projectRoot.name, files) { _, _ -> }
    }
}
