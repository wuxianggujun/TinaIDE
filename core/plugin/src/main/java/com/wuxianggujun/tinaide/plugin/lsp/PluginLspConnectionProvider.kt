package com.wuxianggujun.tinaide.plugin.lsp

import com.wuxianggujun.tinaide.core.linux.LinuxEnvironment
import com.wuxianggujun.tinaide.core.linux.LinuxEnvironmentProvider
import com.wuxianggujun.tinaide.core.linux.LinuxInteractiveProcess
import com.wuxianggujun.tinaide.core.linux.UnavailableLinuxEnvironmentProvider
import com.wuxianggujun.tinaide.core.lsp.LspConnectionProvider
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import timber.log.Timber

/**
 * 通用的插件 LSP 连接提供者
 *
 * 根据 LspServerConfig 配置启动 LSP 服务器进程
 */
class PluginLspConnectionProvider(
    private val config: LspServerConfig,
    val ownerPluginId: String = config.id,
    private val workingDir: String,
    private val projectRoot: String,
    private val linuxEnvironmentProvider: LinuxEnvironmentProvider = UnavailableLinuxEnvironmentProvider,
    private val onStderrLine: (String) -> Unit = {},
    private val onUnexpectedExit: (String) -> Unit = {},
    private val onOwnerStopped: () -> Unit = {},
) : LspConnectionProvider {

    companion object {
        private const val TAG = "PluginLspConnection"
        private const val COMMAND_PROBE_TIMEOUT_MS = 5_000L
    }

    private val linuxEnvironment: LinuxEnvironment by lazy { linuxEnvironmentProvider.get() }

    @Volatile
    private var process: LinuxInteractiveProcess? = null

    @Volatile
    private var stderrThread: Thread? = null

    @Volatile
    private var stopping = false

    private val ownerStopNotified = AtomicBoolean(false)

    override fun start() {
        val serverConfig = config.server

        when (val type = serverConfig.type.trim().lowercase()) {
            "stdio" -> startStdioServer(serverConfig)
            "socket", "websocket" -> throw IllegalStateException(
                "LSP server transport '$type' is declared by plugin '${config.id}', " +
                    "but only stdio transport is currently supported.",
            )
            else -> throw IllegalArgumentException("Unsupported server type: ${serverConfig.type}")
        }
    }

    private fun startStdioServer(serverConfig: LspServerConnectionConfig) {
        val command = serverConfig.command
            ?: throw IllegalArgumentException("No command specified for stdio server")
        validateCommand(command, serverConfig.args.orEmpty(), serverConfig.env.orEmpty())
        val ownerLease = PluginLspSessionRegistry.acquire(ownerPluginId)
            ?: throw IllegalStateException("LSP plugin owner '$ownerPluginId' is not active")

        val args = (serverConfig.args ?: emptyList()).map { arg ->
            expandVariables(arg)
        }

        val env = (serverConfig.env ?: emptyMap()).mapValues { (_, value) ->
            expandVariables(value)
        }

        val escapedCommand = shellEscape(command)
        val commandMissingMessage = shellEscape("LSP server command not found: $command")

        // 构建完整命令：先确认命令存在，再用 exec 接管 shell 进程。
        val fullCommand = buildString {
            append("command -v ")
            append(escapedCommand)
            append(" >/dev/null 2>&1 || { echo ")
            append(commandMissingMessage)
            append(" >&2; exit 127; }; exec ")
            append(escapedCommand)
            if (args.isNotEmpty()) {
                append(" ")
                append(args.joinToString(" ") { shellEscape(it) })
            }
        }

        Timber.tag(TAG).i(
            "Starting plugin LSP owner=%s server=%s command=%s args=%d",
            ownerPluginId,
            config.id,
            command.substringAfterLast('/'),
            args.size,
        )

        stopping = false
        check(linuxEnvironment.isAvailable()) { "Linux environment is unavailable" }

        // 合并环境变量
        val mergedEnv = buildMap {
            // 添加 PATH 以确保能找到用户安装的工具
            put("PATH", "/root/.local/bin:/usr/local/bin:/usr/bin:/bin")
            // 添加配置的环境变量
            putAll(env)
        }
        val guestWorkingDir = linuxEnvironment.toGuestPath(workingDir)
        verifyServerCommandAvailable(
            command = command,
            escapedCommand = escapedCommand,
            guestWorkingDir = guestWorkingDir,
            env = mergedEnv,
        )

        process = linuxEnvironment.startInteractive(
            command = listOf("/bin/sh", "-c", fullCommand),
            workDir = guestWorkingDir,
            env = mergedEnv
        )
        if (!PluginLspSessionRegistry.register(ownerLease, this)) {
            closeFromOwner()
            throw IllegalStateException("LSP plugin owner '$ownerPluginId' stopped during startup")
        }

        // 消费 stderr，防止缓冲区满导致阻塞
        val p = process ?: return
        stderrThread = thread(name = "lsp-stderr-${config.id}", isDaemon = true) {
            runCatching {
                p.stderr.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (stopping) break
                        if (line.isNotBlank()) {
                            Timber.tag(TAG).d("[${config.id}] stderr: $line")
                            onStderrLine(line)
                        }
                    }
                }
            }.onFailure { e ->
                if (!stopping) {
                    Timber.tag(TAG).d("stderr reader stopped for ${config.id}: ${e.message}")
                }
            }
            if (!stopping && !p.isRunning()) {
                onUnexpectedExit("LSP server '${config.id}' exited unexpectedly")
            }
        }
    }

    private fun validateCommand(command: String, args: List<String>, env: Map<String, String>) {
        require(LspServerCommandPolicy.isValid(command, args, env)) { "Invalid LSP command configuration" }
    }

    private fun verifyServerCommandAvailable(
        command: String,
        escapedCommand: String,
        guestWorkingDir: String,
        env: Map<String, String>,
    ) {
        // start() 为同步 API；探测必须在 IO 调度器上，避免误占主线程。
        val probeResult = runBlocking(Dispatchers.IO) {
            linuxEnvironment.execute(
                command = listOf("/bin/sh", "-lc", "command -v $escapedCommand >/dev/null 2>&1"),
                workDir = guestWorkingDir,
                env = env,
                timeout = COMMAND_PROBE_TIMEOUT_MS,
            )
        }
        if (probeResult.exitCode == 0) return

        val message = "LSP server command not found: $command"
        Timber.tag(TAG).w("[%s] %s", config.id, message)
        onStderrLine(message)
        throw IllegalStateException(message)
    }

    /**
     * 展开变量
     *
     * 支持的变量：
     * - ${workspaceRoot} / ${workspaceFolder}: 项目根目录
     * - ${userHome}: 用户主目录
     * - ${pluginDir}: 插件目录（如果需要）
     */
    private fun expandVariables(value: String): String {
        val guestProjectRoot = linuxEnvironment.toGuestPath(projectRoot)
        return value
            .replace("\${workspaceRoot}", guestProjectRoot)
            .replace("\${workspaceFolder}", guestProjectRoot)
            .replace("\${userHome}", "/root")
            .replace("\${HOME}", "/root")
    }

    /**
     * Shell 转义
     */
    private fun shellEscape(value: String): String {
        // 如果值不包含特殊字符，直接返回
        if (value.matches(Regex("^[a-zA-Z0-9_/.-]+$"))) {
            return value
        }
        // 使用单引号转义
        return "'" + value.replace("'", "'\\''") + "'"
    }

    override val inputStream: InputStream
        get() = requireNotNull(process) { "LSP process not started" }.stdout

    override val outputStream: OutputStream
        get() = requireNotNull(process) { "LSP process not started" }.stdin

    override fun close() {
        Timber.tag(TAG).i("Closing LSP connection for ${config.id}")
        stopping = true
        PluginLspSessionRegistry.unregister(ownerPluginId, this)
        runCatching { stderrThread?.interrupt() }
        stderrThread = null
        runCatching { process?.destroy() }
        process = null
    }

    internal fun closeFromOwner() {
        close()
        if (ownerStopNotified.compareAndSet(false, true)) {
            runCatching(onOwnerStopped)
                .onFailure { error ->
                    Timber.tag(TAG).w(error, "Owner stop callback failed for ${config.id}")
                }
        }
    }

    /**
     * 检查进程是否存活
     */
    fun isAlive(): Boolean = process?.isRunning() == true

    fun reportProtocolFailure(error: Throwable) {
        if (stopping) return
        val detail = error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName
        onUnexpectedExit("LSP server '${config.id}' protocol failed: $detail")
    }
}
