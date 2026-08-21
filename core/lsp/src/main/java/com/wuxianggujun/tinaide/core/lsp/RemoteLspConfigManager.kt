package com.wuxianggujun.tinaide.core.lsp

import android.content.Context
import com.wuxianggujun.tinaide.core.config.ConfigKeys
import com.wuxianggujun.tinaide.core.config.IConfigManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 远程 LSP 同步模式
 */
enum class RemoteLspSyncMode(val value: String) {
    AUTO("auto"), // 自动判断
    LIGHTWEIGHT("lightweight"), // 轻量模式
    PROJECT("project"); // 项目模式

    companion object {
        fun fromString(value: String): RemoteLspSyncMode = entries.find { it.value == value } ?: AUTO
    }
}

/**
 * 远程 LSP 同步方案
 */
enum class RemoteLspSyncMethod(val value: String) {
    BUILTIN("builtin"), // 内置 WebSocket 同步
    RSYNC("rsync"), // rsync 增量同步
    MANUAL("manual"); // 手动同步

    companion object {
        fun fromString(value: String): RemoteLspSyncMethod = entries.find { it.value == value } ?: BUILTIN
    }
}

/**
 * 远程 LSP 连接状态
 */
enum class RemoteLspConnectionState {
    DISCONNECTED, // 未连接
    CONNECTING, // 连接中
    CONNECTED, // 已连接
    ERROR // 连接错误
}

/**
 * 远程 LSP 配置数据类
 */
data class RemoteLspConfig(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = 6789,
    val secureTransport: Boolean = true,
    val hasAuthenticationToken: Boolean = false,
    val syncMode: RemoteLspSyncMode = RemoteLspSyncMode.AUTO,
    val syncMethod: RemoteLspSyncMethod = RemoteLspSyncMethod.BUILTIN,
    val rsyncModule: String = "tina-workspace",
    val rsyncPort: Int = 873,
    val remoteWorkspaceRootUri: String = ""
) {
    fun getNormalizedHostForConnection(): String {
        val trimmed = host.trim()
        return when {
            trimmed == "127.0.0.1" || trimmed == "::1" || trimmed == "[::1]" -> "localhost"
            else -> trimmed
        }
    }

    /**
     * 检查配置是否有效（可以尝试连接）
     */
    fun isValid(): Boolean = enabled &&
        host.isNotBlank() &&
        hasValidHostSyntax() &&
        port in 1..65535 &&
        hasAuthenticationToken &&
        (secureTransport || isLoopbackHost())

    /**
     * 获取 WebSocket URL
     */
    fun getWebSocketUrl(): String {
        val normalizedHost = getNormalizedHostForConnection()
        val scheme = if (secureTransport) "wss" else "ws"
        require(hasValidHostSyntax()) { "Remote LSP host is invalid" }
        require(port in 1..65535) { "Remote LSP port is invalid" }
        require(secureTransport || isLoopbackHost()) { "Cleartext remote LSP is restricted to loopback hosts" }
        return "$scheme://$normalizedHost:$port"
    }

    fun hasValidHostSyntax(): Boolean {
        val value = getNormalizedHostForConnection()
        if (value.isBlank() || value.length > MAX_HOST_LENGTH || value.any { it.isWhitespace() || it in "/@?#" }) {
            return false
        }
        return if (value.startsWith('[') || value.endsWith(']')) {
            value.startsWith('[') && value.endsWith(']') &&
                value.substring(1, value.length - 1).contains(':') &&
                value.substring(1, value.length - 1).matches(Regex("(?i)^[0-9a-f:.]+$")) &&
                runCatching { java.net.URI("http://$value").host != null }.getOrDefault(false)
        } else {
            value.split('.').all { label ->
                label.length in 1..63 &&
                    label.matches(Regex("(?i)^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$"))
            }
        }
    }

    private fun isLoopbackHost(): Boolean =
        getNormalizedHostForConnection().equals("localhost", ignoreCase = true)

    private companion object {
        const val MAX_HOST_LENGTH = 253
    }
}

/**
 * 远程 LSP 配置管理器
 *
 * 提供远程 LSP 配置的读写和状态管理。
 * 使用 StateFlow 支持响应式更新。
 */
object RemoteLspConfigManager {

    @Volatile
    private var configManager: IConfigManager? = null
    @Volatile
    private var credentialStore: RemoteLspCredentialStore? = null

    fun install(context: Context, configManager: IConfigManager) {
        this.configManager = configManager
        this.credentialStore = RemoteLspCredentialStore(context)
        refresh()
    }

    private fun requireConfigManager(): IConfigManager = checkNotNull(configManager) {
        "RemoteLspConfigManager is not installed. Call RemoteLspConfigManager.install(context, configManager) from app init."
    }

    // 配置状态流
    private val _configFlow = MutableStateFlow(RemoteLspConfig())
    val configFlow: StateFlow<RemoteLspConfig> = _configFlow.asStateFlow()

    // 连接状态流
    private val _connectionStateFlow = MutableStateFlow(RemoteLspConnectionState.DISCONNECTED)
    val connectionStateFlow: StateFlow<RemoteLspConnectionState> = _connectionStateFlow.asStateFlow()

    // 连接错误信息
    private val _connectionErrorFlow = MutableStateFlow<String?>(null)
    val connectionErrorFlow: StateFlow<String?> = _connectionErrorFlow.asStateFlow()

    // 连接延迟（毫秒）
    private val _latencyMsFlow = MutableStateFlow(0L)
    val latencyMsFlow: StateFlow<Long> = _latencyMsFlow.asStateFlow()

    // 重连尝试次数
    private val _reconnectAttemptFlow = MutableStateFlow(0)
    val reconnectAttemptFlow: StateFlow<Int> = _reconnectAttemptFlow.asStateFlow()

    // 自动检测结果（仅在 AUTO 模式下有效）
    private val _detectedSyncModeFlow = MutableStateFlow<Pair<RemoteLspSyncMode, String>?>(null)
    val detectedSyncModeFlow: StateFlow<Pair<RemoteLspSyncMode, String>?> = _detectedSyncModeFlow.asStateFlow()

    /**
     * 获取当前配置
     */
    val config: RemoteLspConfig
        get() = _configFlow.value

    /**
     * 是否启用远程 LSP
     */
    val isEnabled: Boolean
        get() = config.enabled

    /**
     * 当前连接状态
     */
    val connectionState: RemoteLspConnectionState
        get() = _connectionStateFlow.value

    /**
     * 从持久化存储加载配置
     */
    private fun loadConfig(configManager: IConfigManager): RemoteLspConfig = RemoteLspConfig(
        enabled = configManager.get(ConfigKeys.RemoteLspEnabled),
        host = configManager.get(ConfigKeys.RemoteLspHost),
        port = configManager.get(ConfigKeys.RemoteLspPort),
        secureTransport = configManager.get(ConfigKeys.RemoteLspSecureTransport),
        hasAuthenticationToken = credentialStore?.readToken() != null,
        syncMode = RemoteLspSyncMode.fromString(configManager.get(ConfigKeys.RemoteLspSyncMode)),
        syncMethod = RemoteLspSyncMethod.fromString(configManager.get(ConfigKeys.RemoteLspSyncMethod)),
        rsyncModule = configManager.get(ConfigKeys.RemoteLspRsyncModule),
        rsyncPort = configManager.get(ConfigKeys.RemoteLspRsyncPort),
        remoteWorkspaceRootUri = configManager.get(ConfigKeys.RemoteLspWorkspaceRootUri)
    )

    /**
     * 刷新配置（从持久化存储重新加载）
     */
    fun refresh() {
        val configManager = configManager ?: return
        _configFlow.value = loadConfig(configManager)
    }

    /**
     * 设置是否启用远程 LSP
     */
    fun setEnabled(enabled: Boolean) {
        val configManager = requireConfigManager()
        configManager.set(ConfigKeys.RemoteLspEnabled, enabled)
        _configFlow.value = _configFlow.value.copy(enabled = enabled)
    }

    /**
     * 设置远程服务器地址
     */
    fun setHost(host: String) {
        val configManager = requireConfigManager()
        configManager.set(ConfigKeys.RemoteLspHost, host)
        _configFlow.value = _configFlow.value.copy(host = host)
    }

    /**
     * 设置远程服务器端口
     */
    fun setPort(port: Int) {
        val configManager = requireConfigManager()
        configManager.set(ConfigKeys.RemoteLspPort, port)
        _configFlow.value = _configFlow.value.copy(port = port)
    }

    fun setSecureTransport(secure: Boolean) {
        val configManager = requireConfigManager()
        configManager.set(ConfigKeys.RemoteLspSecureTransport, secure)
        _configFlow.value = _configFlow.value.copy(secureTransport = secure)
    }

    fun setAuthenticationToken(token: String?) {
        val store = checkNotNull(credentialStore) { "Remote LSP credential store is not installed" }
        store.writeToken(token)
        _configFlow.value = _configFlow.value.copy(hasAuthenticationToken = store.readToken() != null)
    }

    fun getAuthenticationToken(): String? = credentialStore?.readToken()

    /**
     * 设置同步模式
     */
    fun setSyncMode(mode: RemoteLspSyncMode) {
        val configManager = requireConfigManager()
        configManager.set(ConfigKeys.RemoteLspSyncMode, mode.value)
        _configFlow.value = _configFlow.value.copy(syncMode = mode)
    }

    /**
     * 设置同步方案
     */
    fun setSyncMethod(method: RemoteLspSyncMethod) {
        val configManager = requireConfigManager()
        configManager.set(ConfigKeys.RemoteLspSyncMethod, method.value)
        _configFlow.value = _configFlow.value.copy(syncMethod = method)
    }

    /**
     * 设置 rsync 模块名称
     */
    fun setRsyncModule(module: String) {
        val configManager = requireConfigManager()
        configManager.set(ConfigKeys.RemoteLspRsyncModule, module)
        _configFlow.value = _configFlow.value.copy(rsyncModule = module)
    }

    /**
     * 设置 rsync daemon 端口
     */
    fun setRsyncPort(port: Int) {
        val configManager = requireConfigManager()
        configManager.set(ConfigKeys.RemoteLspRsyncPort, port)
        _configFlow.value = _configFlow.value.copy(rsyncPort = port)
    }

    fun setRemoteWorkspaceRootUri(uri: String) {
        val configManager = requireConfigManager()
        configManager.set(ConfigKeys.RemoteLspWorkspaceRootUri, uri)
        _configFlow.value = _configFlow.value.copy(remoteWorkspaceRootUri = uri)
    }

    /**
     * 批量更新配置
     */
    fun updateConfig(
        enabled: Boolean? = null,
        host: String? = null,
        port: Int? = null,
        syncMode: RemoteLspSyncMode? = null,
        syncMethod: RemoteLspSyncMethod? = null,
        rsyncModule: String? = null,
        rsyncPort: Int? = null,
        remoteWorkspaceRootUri: String? = null
    ) {
        enabled?.let { setEnabled(it) }
        host?.let { setHost(it) }
        port?.let { setPort(it) }
        syncMode?.let { setSyncMode(it) }
        syncMethod?.let { setSyncMethod(it) }
        rsyncModule?.let { setRsyncModule(it) }
        rsyncPort?.let { setRsyncPort(it) }
        remoteWorkspaceRootUri?.let { setRemoteWorkspaceRootUri(it) }
    }

    /**
     * 更新连接状态
     */
    fun updateConnectionState(state: RemoteLspConnectionState, error: String? = null) {
        _connectionStateFlow.value = state
        _connectionErrorFlow.value = error
        // 连接成功时重置重连计数
        if (state == RemoteLspConnectionState.CONNECTED) {
            _reconnectAttemptFlow.value = 0
        }
    }

    /**
     * 更新延迟信息
     */
    fun updateLatency(latencyMs: Long) {
        _latencyMsFlow.value = latencyMs
    }

    /**
     * 更新重连尝试次数
     */
    fun updateReconnectAttempt(attempt: Int) {
        _reconnectAttemptFlow.value = attempt
    }

    /**
     * 更新自动检测结果
     */
    fun updateDetectedSyncMode(mode: RemoteLspSyncMode, reason: String) {
        _detectedSyncModeFlow.value = Pair(mode, reason)
    }

    /**
     * 清除自动检测结果
     */
    fun clearDetectedSyncMode() {
        _detectedSyncModeFlow.value = null
    }

    /**
     * 重置为默认配置
     */
    fun resetToDefaults() {
        setEnabled(false)
        setHost("")
        setPort(6789)
        setSecureTransport(true)
        setAuthenticationToken(null)
        setSyncMode(RemoteLspSyncMode.AUTO)
        setSyncMethod(RemoteLspSyncMethod.BUILTIN)
        setRsyncModule("tina-workspace")
        setRsyncPort(873)
        setRemoteWorkspaceRootUri("")
        clearDetectedSyncMode()
        updateLatency(0L)
        updateReconnectAttempt(0)
        updateConnectionState(RemoteLspConnectionState.DISCONNECTED)
    }
}
