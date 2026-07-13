package com.wuxianggujun.tinaide.core.lsp

import com.wuxianggujun.tinaide.core.i18n.AppStrings
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.network.OkHttpClientProvider
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import timber.log.Timber

/**
 * 连接状态
 */
enum class ConnectionState {
    DISCONNECTED, // 未连接
    CONNECTING, // 连接中
    CONNECTED, // 已连接
    RECONNECTING, // 重连中
    FAILED // 连接失败
}

/**
 * 连接事件
 */
sealed class ConnectionEvent {
    data object Connected : ConnectionEvent()
    data object Disconnected : ConnectionEvent()
    data class Reconnecting(val attempt: Int, val maxAttempts: Int) : ConnectionEvent()
    data class Error(val message: String, val cause: Throwable?) : ConnectionEvent()
    data class LatencyUpdate(val latencyMs: Long) : ConnectionEvent()
}

/**
 * 连接状态监听器
 */
interface ConnectionStateListener {
    fun onStateChanged(state: ConnectionState)
    fun onEvent(event: ConnectionEvent)
}

/**
 * 远程 LSP 连接提供者
 *
 * 通过 WebSocket 连接远程 LSP 服务器（如 PC 上的 lsp-ws-proxy），
 * 提供标准输入输出流给上层 LSP 客户端。
 *
 * 特性：
 * - 自动重连机制（指数退避）
 * - 连接状态回调
 * - 延迟监控
 * - 协程支持
 *
 * @param host 远程服务器地址
 * @param port 远程服务器端口
 * @param connectTimeoutMs 连接超时时间（毫秒）
 * @param autoReconnect 是否启用自动重连
 * @param maxReconnectAttempts 最大重连次数
 */
class RemoteLspConnectionProvider(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Long = 10_000L,
    private val autoReconnect: Boolean = true,
    private val maxReconnectAttempts: Int = 5
) : LspConnectionProvider {

    companion object {
        private const val TAG = "RemoteLsp"
        private const val PIPE_BUFFER_SIZE = 64 * 1024 // 64KB 缓冲区
        private const val BASE_RECONNECT_DELAY_MS = 1000L // 基础重连延迟
        private const val MAX_RECONNECT_DELAY_MS = 30_000L // 最大重连延迟
        private const val OUTBOUND_RETRY_DELAY_MS = 50L
    }

    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // OkHttp 客户端
    private val client = OkHttpClientProvider.custom(OkHttpClientProvider.longConnection) {
        connectTimeout(connectTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    // WebSocket 连接
    @Volatile
    private var webSocket: WebSocket? = null

    // 管道流
    private lateinit var inputPipeIn: PipedInputStream
    private lateinit var inputPipeOut: PipedOutputStream
    private lateinit var outputPipeIn: PipedInputStream
    private lateinit var outputPipeOut: PipedOutputStream

    // 输出转发协程
    private var outputForwardJob: Job? = null
    private val startMutex = Mutex()

    // 连接状态
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // 连接标志
    private val connected = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val connectionGeneration = AtomicLong(0L)
    private val lifecycleLock = Any()

    // 重连相关
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null

    // 延迟监控
    private val lastPingTime = AtomicLong(0)
    private val _latencyMs = MutableStateFlow(0L)
    val latencyMs: StateFlow<Long> = _latencyMs.asStateFlow()

    // URI 映射（Android 本地路径 <-> 远端工作区路径）
    private val uriMapper = RemoteLspUriMapper()

    // JSON-RPC request 等待表（用于 tina/syncProject 等“非 LSP 标准”消息）
    private val pendingRequests = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()
    private val requestIdGen = AtomicLong(0L)

    // 连接完成通道
    @Volatile
    private var connectChannel: Channel<Result<Unit>>? = null

    // 状态监听器
    private val listeners = mutableListOf<ConnectionStateListener>()

    /**
     * 添加状态监听器
     */
    fun addStateListener(listener: ConnectionStateListener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    /**
     * 移除状态监听器
     */
    fun removeStateListener(listener: ConnectionStateListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    private fun notifyStateChanged(state: ConnectionState) {
        _connectionState.value = state
        synchronized(listeners) {
            listeners.forEach { it.onStateChanged(state) }
        }
    }

    private fun notifyEvent(event: ConnectionEvent) {
        synchronized(listeners) {
            listeners.forEach { it.onEvent(event) }
        }
    }

    @Throws(IOException::class)
    override fun start() {
        Timber.tag(TAG).i("Connecting to remote LSP server: ws://$host:$port")

        if (!prepareForManualConnection()) throw closedConnectionException()

        // 同步连接（start() 可能被 LSP 框架重复调用，必须幂等）
        runBlocking {
            startMutex.withLock {
                throwIfClosed()
                ensurePipes()
                if (!connected.get()) {
                    connectWithTimeout().getOrThrow()
                }
            }
        }
    }

    /**
     * 异步启动连接
     */
    suspend fun startAsync(): Result<Unit> {
        Timber.tag(TAG).i("Async connecting to remote LSP server: ws://$host:$port")

        if (!prepareForManualConnection()) return Result.failure(closedConnectionException())

        return startMutex.withLock {
            if (closed.get()) return@withLock Result.failure(closedConnectionException())
            ensurePipes()
            if (connected.get()) {
                Result.success(Unit)
            } else {
                connectWithTimeout()
            }
        }
    }

    private fun ensurePipes() {
        if (::inputPipeIn.isInitialized && ::outputPipeIn.isInitialized) return
        inputPipeIn = PipedInputStream(PIPE_BUFFER_SIZE)
        inputPipeOut = PipedOutputStream(inputPipeIn)
        outputPipeIn = PipedInputStream(PIPE_BUFFER_SIZE)
        outputPipeOut = PipedOutputStream(outputPipeIn)
    }

    private suspend fun connectWithTimeout(): Result<Unit> {
        val request = try {
            Request.Builder()
                .url("ws://$host:$port")
                .build()
        } catch (error: IllegalArgumentException) {
            return Result.failure(
                IOException(AppStrings.get(Strings.editor_lsp_connection_failed, getConnectionInfo()), error)
            )
        }
        val resultChannel = Channel<Result<Unit>>(1)
        val generation = synchronized(lifecycleLock) connectBlock@{
            if (closed.get() || stopping.get()) return@connectBlock null
            connectionGeneration.incrementAndGet().also {
                connectChannel = resultChannel
                notifyStateChanged(ConnectionState.CONNECTING)
            }
        }
        if (generation == null) {
            resultChannel.close()
            return Result.failure(closedConnectionException())
        }
        if (!isCurrentConnection(generation)) {
            resultChannel.close()
            return Result.failure(closedConnectionException())
        }

        val socket = try {
            client.newWebSocket(request, createWebSocketListener(generation, resultChannel))
        } catch (error: RuntimeException) {
            val failure = IOException(
                AppStrings.get(Strings.editor_lsp_connection_failed, getConnectionInfo()),
                error,
            )
            synchronized(lifecycleLock) {
                if (connectionGeneration.compareAndSet(generation, generation + 1)) {
                    if (connectChannel === resultChannel) connectChannel = null
                    connected.set(false)
                    if (!closed.get() && !stopping.get()) {
                        notifyStateChanged(ConnectionState.FAILED)
                        if (!closed.get() && !stopping.get()) {
                            notifyEvent(ConnectionEvent.Error(failure.message.orEmpty(), failure))
                        }
                    }
                }
            }
            resultChannel.close()
            return Result.failure(failure)
        }
        val socketAccepted = synchronized(lifecycleLock) {
            if (isCurrentConnection(generation)) {
                webSocket = socket
                true
            } else {
                false
            }
        }
        if (!socketAccepted) socket.cancel()

        // 等待连接结果
        return try {
            val result = withTimeoutOrNull(connectTimeoutMs) {
                resultChannel.receive()
            } ?: run {
                val timeoutAccepted = synchronized(lifecycleLock) {
                    if (closed.get() || stopping.get() ||
                        !connectionGeneration.compareAndSet(generation, generation + 1)
                    ) {
                        false
                    } else {
                        if (webSocket === socket) webSocket = null
                        connected.set(false)
                        notifyStateChanged(ConnectionState.FAILED)
                        if (closed.get() || stopping.get()) {
                            false
                        } else {
                            notifyEvent(
                                ConnectionEvent.Error(
                                    AppStrings.get(Strings.editor_lsp_connection_timeout),
                                    null,
                                )
                            )
                            !closed.get() && !stopping.get()
                        }
                    }
                }
                socket.cancel()
                Result.failure(
                    if (!timeoutAccepted && closed.get()) {
                        closedConnectionException()
                    } else {
                        IOException(
                            AppStrings.get(
                                Strings.editor_lsp_connection_failed,
                                "ws://$host:$port"
                            )
                        )
                    }
                )
            }
            if (result.isSuccess && (!isCurrentConnection(generation) || !connected.get())) {
                Result.failure(
                    IOException(AppStrings.get(Strings.editor_lsp_connection_failed, getConnectionInfo()))
                )
            } else {
                result
            }
        } finally {
            synchronized(lifecycleLock) {
                if (connectChannel === resultChannel) connectChannel = null
            }
            resultChannel.close()
        }
    }

    private fun createWebSocketListener(
        generation: Long,
        resultChannel: Channel<Result<Unit>>,
    ) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val accepted = synchronized(lifecycleLock) {
                if (!isCurrentConnection(generation)) {
                    false
                } else {
                    Timber.tag(TAG).i("WebSocket connected to $host:$port")
                    connected.set(true)
                    reconnectAttempt = 0
                    notifyStateChanged(ConnectionState.CONNECTED)
                    if (!isCurrentConnection(generation)) {
                        false
                    } else {
                        notifyEvent(ConnectionEvent.Connected)
                        if (!isCurrentConnection(generation)) {
                            false
                        } else {
                            startOutputForwarding()
                            resultChannel.trySend(Result.success(Unit))
                            true
                        }
                    }
                }
            }
            if (!accepted) {
                webSocket.close(1000, "Stale connection")
                return
            }

            Timber.tag(TAG).i("Remote LSP connection established")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrentConnection(generation)) return

            // 更新延迟（如果是 pong 响应）
            val pingTime = lastPingTime.get()
            if (pingTime > 0) {
                val latency = System.currentTimeMillis() - pingTime
                _latencyMs.value = latency
                notifyEvent(ConnectionEvent.LatencyUpdate(latency))
                lastPingTime.set(0)
            }

            // 先处理/吞掉 tina/* 的“非 LSP 标准”消息，避免 LSP 框架报 Unsupported notification
            runCatching {
                val json = JSONObject(text)

                val idValue = json.opt("id")
                if (idValue != null && idValue != JSONObject.NULL) {
                    val idLong = when (idValue) {
                        is Number -> idValue.toLong()
                        else -> idValue.toString().toLongOrNull()
                    }
                    if (idLong != null) {
                        val deferred = pendingRequests.remove(idLong)
                        if (deferred != null) {
                            deferred.complete(json)
                            return
                        }
                    }
                }

                val method = json.optString("method")
                if (method.startsWith("tina/") && method.endsWith("Response")) {
                    return
                }
            }.onFailure {
                // ignore parse errors, still forward to LSP pipeline
            }

            val rewritten = uriMapper.rewriteServerToClient(text)

            try {
                // LSP 消息需要添加 Content-Length 头（按 UTF-8 字节长度）
                val contentBytes = rewritten.toByteArray(Charsets.UTF_8)
                val header = "Content-Length: ${contentBytes.size}\r\n\r\n"
                synchronized(inputPipeOut) {
                    inputPipeOut.write(header.toByteArray(Charsets.UTF_8))
                    inputPipeOut.write(contentBytes)
                    inputPipeOut.flush()
                }
            } catch (e: IOException) {
                if (!stopping.get()) {
                    Timber.tag(TAG).e(e, "Error writing to input pipe")
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            onMessage(webSocket, bytes.utf8())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Timber.tag(TAG).i("WebSocket closing: code=$code, reason=$reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Timber.tag(TAG).i("WebSocket closed: code=$code, reason=$reason")
            handleDisconnect(generation)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!isCurrentConnection(generation)) return
            Timber.tag(TAG).e(t, "WebSocket connection failed")

            // 如果还在连接阶段，发送失败结果
            resultChannel.trySend(
                Result.failure(
                    IOException(
                        AppStrings.get(
                            Strings.editor_lsp_connection_failed,
                            t.message ?: AppStrings.get(Strings.error_unknown)
                        ),
                        t
                    )
                )
            )

            handleDisconnect(generation, t)
        }
    }

    private fun handleDisconnect(generation: Long, error: Throwable? = null) {
        synchronized(lifecycleLock) disconnectBlock@{
            if (closed.get() || stopping.get()) return@disconnectBlock
            if (!connectionGeneration.compareAndSet(generation, generation + 1)) {
                return@disconnectBlock
            }
            webSocket = null
            if (connected.getAndSet(false)) {
                notifyEvent(ConnectionEvent.Disconnected)
                if (closed.get() || stopping.get()) return@disconnectBlock
                error?.let {
                    notifyEvent(ConnectionEvent.Error(it.message ?: AppStrings.get(Strings.error_unknown), it))
                }
                if (closed.get() || stopping.get()) return@disconnectBlock
            }

            val willReconnect = autoReconnect && reconnectAttempt < maxReconnectAttempts
            if (willReconnect) {
                scheduleReconnect()
            } else {
                notifyStateChanged(ConnectionState.DISCONNECTED)
            }
        }
    }

    private fun scheduleReconnect() {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var lastError: Throwable? = null
            while (true) {
                val retry = synchronized(lifecycleLock) retryBlock@{
                    if (closed.get() || stopping.get() || connected.get() ||
                        reconnectAttempt >= maxReconnectAttempts
                    ) {
                        return@retryBlock null
                    }
                    reconnectAttempt++
                    val currentAttempt = reconnectAttempt
                    val reconnectDelayMs = calculateReconnectDelay(currentAttempt)
                    Timber.tag(TAG).i(
                        "Scheduling reconnect attempt %d/%d in %dms",
                        currentAttempt,
                        maxReconnectAttempts,
                        reconnectDelayMs,
                    )
                    notifyStateChanged(ConnectionState.RECONNECTING)
                    if (closed.get() || stopping.get()) return@retryBlock null
                    notifyEvent(ConnectionEvent.Reconnecting(currentAttempt, maxReconnectAttempts))
                    if (closed.get() || stopping.get()) return@retryBlock null
                    currentAttempt to reconnectDelayMs
                } ?: break

                delay(retry.second)
                if (closed.get() || stopping.get()) return@launch

                val result = startMutex.withLock {
                    if (closed.get()) {
                        Result.failure(closedConnectionException())
                    } else if (connected.get()) {
                        Result.success(Unit)
                    } else {
                        ensurePipes()
                        connectWithTimeout()
                    }
                }
                if (result.isSuccess) return@launch
                lastError = result.exceptionOrNull()
                Timber.tag(TAG).w(lastError, "Reconnect attempt %d failed", retry.first)
            }

            synchronized(lifecycleLock) {
                if (!closed.get() && !stopping.get() && !connected.get()) {
                    notifyStateChanged(ConnectionState.FAILED)
                    if (!closed.get() && !stopping.get()) {
                        notifyEvent(
                            ConnectionEvent.Error(
                                AppStrings.get(Strings.editor_lsp_reconnect_max_attempts),
                                lastError,
                            ),
                        )
                    }
                }
            }
        }
        val accepted = synchronized(lifecycleLock) {
            if (closed.get() || stopping.get() || connected.get() || reconnectJob != null) {
                false
            } else {
                reconnectJob = job
                true
            }
        }
        if (!accepted) {
            job.cancel()
            return
        }
        job.invokeOnCompletion {
            synchronized(lifecycleLock) {
                if (reconnectJob === job) reconnectJob = null
            }
        }
        job.start()
    }

    /**
     * 计算重连延迟（指数退避）
     */
    private fun calculateReconnectDelay(attempt: Int): Long {
        val delay = BASE_RECONNECT_DELAY_MS * (1 shl (attempt - 1).coerceAtMost(5))
        return delay.coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }

    private fun startOutputForwarding() {
        synchronized(lifecycleLock) outputBlock@{
            if (closed.get() || outputForwardJob?.isActive == true) return@outputBlock
            outputForwardJob = scope.launch {
                forwardOutputToWebSocket()
            }
        }
    }

    /**
     * 从输出管道读取 LSP 消息并发送到 WebSocket
     */
    private suspend fun forwardOutputToWebSocket() {
        try {
            withContext(Dispatchers.IO) {
                while (isActive && !closed.get()) {
                    val contentLength = readContentLength(outputPipeIn) ?: break
                    if (contentLength <= 0) continue

                    val bodyBytes = ByteArray(contentLength)
                    var totalRead = 0
                    while (totalRead < contentLength) {
                        val read = outputPipeIn.read(bodyBytes, totalRead, contentLength - totalRead)
                        if (read == -1) return@withContext
                        totalRead += read
                    }

                    var message = String(bodyBytes, Charsets.UTF_8)
                    captureInitializeRootUriIfNeeded(message)
                    message = uriMapper.rewriteClientToServer(message)
                    while (isActive && !closed.get()) {
                        val socket = webSocket.takeIf { connected.get() }
                        if (socket != null && socket.send(message)) break
                        delay(OUTBOUND_RETRY_DELAY_MS)
                    }
                }
            }
        } catch (e: Exception) {
            if (!stopping.get()) {
                Timber.tag(TAG).e(e, "Error forwarding output to WebSocket")
            }
        }
    }

    private fun readContentLength(input: InputStream): Int? {
        var contentLength: Int? = null
        while (true) {
            val line = readAsciiLine(input) ?: return null
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            if (key.equals("Content-Length", ignoreCase = true)) {
                contentLength = value.toIntOrNull()
            }
        }
        return contentLength
    }

    private fun readAsciiLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isNotEmpty()) sb.toString() else null
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun captureInitializeRootUriIfNeeded(message: String) {
        if (uriMapper.hasMapping()) return
        runCatching {
            val json = JSONObject(message)
            if (json.optString("method") != "initialize") return
            val params = json.optJSONObject("params") ?: return
            val rootUri = params.optString("rootUri").takeIf { it.isNotBlank() }
                ?: params.optJSONArray("workspaceFolders")?.optJSONObject(0)?.optString("uri")?.takeIf { it.isNotBlank() }
            rootUri?.let(uriMapper::setClientRootUri)
        }
    }

    override val inputStream: InputStream
        get() = inputPipeIn

    override val outputStream: OutputStream
        get() = outputPipeOut

    override fun close() {
        synchronized(lifecycleLock) closeBlock@{
            if (!closed.compareAndSet(false, true)) return@closeBlock
            Timber.tag(TAG).i("Closing remote LSP connection")
            stopping.set(true)
            connectionGeneration.incrementAndGet()

            val closeError = closedConnectionException()
            connectChannel?.trySend(Result.failure(closeError))
            connectChannel?.close()
            connectChannel = null

            reconnectJob?.cancel()
            reconnectJob = null
            outputForwardJob?.cancel()
            outputForwardJob = null

            runCatching { webSocket?.close(1000, "Client closed") }
            webSocket = null
            closeStreams()

            val cancellation = CancellationException(closeError.message)
            pendingRequests.values.forEach { request -> request.cancel(cancellation) }
            pendingRequests.clear()

            connected.set(false)
            notifyStateChanged(ConnectionState.DISCONNECTED)
            scope.cancel(cancellation)
            Timber.tag(TAG).i("Remote LSP connection closed")
        }
    }

    private fun closeStreams() {
        runCatching { inputPipeOut.close() }
        runCatching { inputPipeIn.close() }
        runCatching { outputPipeOut.close() }
        runCatching { outputPipeIn.close() }
    }

    /**
     * 检查连接是否已建立
     */
    fun isConnected(): Boolean = connected.get()

    /**
     * 获取连接信息
     */
    fun getConnectionInfo(): String = "ws://$host:$port"

    /**
     * 手动触发重连
     */
    fun reconnect() {
        if (!prepareForManualConnection()) {
            Timber.tag(TAG).w("Connection provider is closed, ignoring reconnect request")
            return
        }
        if (connected.get()) {
            Timber.tag(TAG).w("Already connected, ignoring reconnect request")
            return
        }

        scheduleReconnect()
    }

    /**
     * 测量延迟（发送 ping）
     */
    fun measureLatency() {
        if (closed.get() || !connected.get()) return
        lastPingTime.set(System.currentTimeMillis())
        // OkHttp 会自动处理 ping/pong，这里我们通过下一条消息的响应时间来估算延迟
    }

    /**
     * 直接发送消息到 WebSocket（用于自定义消息，如项目同步）
     */
    fun sendRawMessage(message: String): Boolean {
        if (closed.get() || !connected.get()) {
            Timber.tag(TAG).w("Cannot send message: not connected")
            return false
        }
        return webSocket?.send(message) ?: false
    }

    fun setClientWorkspaceRootUri(uri: String?) {
        uriMapper.setClientRootUri(uri)
    }

    fun setRemoteWorkspaceRootUri(uriOrPath: String?) {
        uriMapper.setServerRootUri(uriOrPath)
    }

    private suspend fun sendJsonRpcRequest(
        method: String,
        params: JSONObject,
        timeoutMs: Long
    ): JSONObject? {
        if (closed.get()) return null
        if (!connected.get()) {
            val started = startAsync()
            if (started.isFailure) return null
        }

        val id = requestIdGen.decrementAndGet()
        val deferred = CompletableDeferred<JSONObject>()
        pendingRequests[id] = deferred

        val request = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }

        val sent = sendRawMessage(request.toString())
        if (!sent) {
            pendingRequests.remove(id)
            return null
        }

        val response = withTimeoutOrNull(timeoutMs) { deferred.await() }
        pendingRequests.remove(id)
        return response
    }

    private fun isCurrentConnection(generation: Long): Boolean =
        !closed.get() && !stopping.get() && connectionGeneration.get() == generation

    private fun prepareForManualConnection(): Boolean = synchronized(lifecycleLock) {
        if (closed.get()) {
            false
        } else {
            stopping.set(false)
            reconnectAttempt = 0
            true
        }
    }

    private fun throwIfClosed() {
        if (closed.get()) throw closedConnectionException()
    }

    private fun closedConnectionException(): IOException = IOException(
        AppStrings.get(Strings.editor_lsp_connection_closed, getConnectionInfo())
    )

    /**
     * 发送项目同步消息
     */
    suspend fun syncProject(
        projectName: String,
        files: List<ProjectFileInfo>,
        onProgress: ((Int, Int) -> Unit)? = null
    ): Boolean {
        if (!connected.get()) {
            Timber.tag(TAG).w("Cannot sync project: not connected")
            return false
        }

        return try {
            // 当前远端工具链（Python/Kotlin 代理）仅保证 tina/syncProject；为稳定性先禁用 chunked
            val (message, _) = ProjectSyncManager.generateSyncMessage(projectName, files)
            val req = JSONObject(message)
            val params = req.optJSONObject("params") ?: JSONObject()

            // 同步项目请求（等待 workspaceRoot，用于 URI 映射）
            val resp = sendJsonRpcRequest("tina/syncProject", params, timeoutMs = 60_000L)
            if (resp == null) {
                Timber.tag(TAG).e("Project sync request timeout/failed: $projectName")
                return false
            }

            val error = resp.optJSONObject("error")
            if (error != null) {
                Timber.tag(TAG).e("Project sync error: ${error.optString("message")}")
                return false
            }

            val result = resp.optJSONObject("result")
            val workspaceRoot = result?.optString("workspaceRoot")?.takeIf { it.isNotBlank() }
            if (workspaceRoot != null) {
                setRemoteWorkspaceRootUri(workspaceRoot)
            }

            ProjectSyncManager.markSynced(projectName, files)
            Timber.tag(TAG).i("Project sync completed: $projectName (${files.size} files)")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to sync project")
            false
        }
    }

    /**
     * 分块同步项目
     */
    private suspend fun syncProjectChunked(
        projectName: String,
        files: List<ProjectFileInfo>,
        onProgress: ((Int, Int) -> Unit)? = null
    ): Boolean {
        val sessionId = ProjectSyncManager.generateSessionId()
        val chunks = ProjectSyncManager.splitIntoChunks(files)
        val totalSize = files.sumOf { it.size }

        Timber.tag(TAG).i("Starting chunked sync: $projectName, ${files.size} files, ${chunks.size} chunks")

        // 发送开始消息
        val startMessage = ProjectSyncManager.generateChunkSyncStartMessage(
            projectName = projectName,
            sessionId = sessionId,
            totalFiles = files.size,
            totalSize = totalSize,
            totalChunks = chunks.size
        )

        if (!sendRawMessage(startMessage)) {
            Timber.tag(TAG).e("Failed to send sync start message")
            return false
        }

        // 逐块发送
        for ((index, chunk) in chunks.withIndex()) {
            val chunkMessage = ProjectSyncManager.generateChunkSyncMessage(
                projectName = projectName,
                chunk = chunk,
                sessionId = sessionId
            )

            if (!sendRawMessage(chunkMessage)) {
                Timber.tag(TAG).e("Failed to send chunk ${index + 1}/${chunks.size}")
                return false
            }

            // 更新进度
            onProgress?.invoke(index + 1, chunks.size)
            ProjectSyncManager.updateChunkProgress(index + 1, chunks.size)

            Timber.tag(TAG).d("Sent chunk ${index + 1}/${chunks.size} (${chunk.files.size} files)")

            // 小延迟，避免消息堆积
            if (index < chunks.size - 1) {
                kotlinx.coroutines.delay(50)
            }
        }

        ProjectSyncManager.markSynced(projectName, files)
        Timber.tag(TAG).i("Chunked sync completed: $projectName (${files.size} files in ${chunks.size} chunks)")
        return true
    }

    /**
     * 发送文件变更消息
     */
    fun sendFileChanged(
        type: String,
        path: String,
        content: String? = null,
        oldPath: String? = null
    ): Boolean {
        if (!connected.get()) {
            Timber.tag(TAG).w("Cannot send file changed: not connected")
            return false
        }

        val message = ProjectSyncManager.generateFileChangedMessage(type, path, content, oldPath)
        val success = sendRawMessage(message)
        if (success) {
            Timber.tag(TAG).d("File changed message sent: $type $path")
        }
        return success
    }

    /**
     * 销毁资源
     */
    fun destroy() {
        close()
        scope.cancel()
    }
}
