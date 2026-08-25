package com.wuxianggujun.tinaide.core.lsp

import android.content.Context
import com.wuxianggujun.tinaide.core.config.ClangdSettings
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.linux.LinuxEnvironment
import com.wuxianggujun.tinaide.core.linux.LinuxInteractiveProcess
import com.wuxianggujun.tinaide.core.linux.UnavailableLinuxEnvironment
import com.wuxianggujun.tinaide.core.proot.ToolchainPathResolver
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.concurrent.thread
import timber.log.Timber

/**
 * 通过 PRoot 启动 clangd，并以 stdio 方式提供 LSP 连接流。
 */
class PRootClangdConnectionProvider(
    context: Context,
    private val workingDir: String,
    private val compileCommandsDir: String,
    private val clangdSettings: ClangdSettings = Prefs.clangdSettingsFlow.value,
    private val linuxEnvironment: LinuxEnvironment = UnavailableLinuxEnvironment,
) : LspConnectionProvider {

    companion object {
        private const val TAG = "PRootClangd"
    }

    private val toolchainPathResolver by lazy {
        ToolchainPathResolver(context.applicationContext)
    }

    @Volatile
    private var process: LinuxInteractiveProcess? = null

    @Volatile
    private var stderrThread: Thread? = null

    @Volatile
    private var stopping = false

    override fun start() {
        check(linuxEnvironment.isAvailable()) { "Linux environment is unavailable" }
        require(compileCommandsDir.isNotBlank()) { "compileCommandsDir must not be blank" }

        if (CompileCommandsDebugLogger.isClangdStartupEnabled()) {
            CompileCommandsDebugLogger.logClangdStartupSummary(
                TAG,
                "proot-clangd-start",
                File(compileCommandsDir, "compile_commands.json")
            )
        }
        val shellCommand = buildClangdCommand()

        Timber.tag(TAG).i("Starting clangd in PRoot: cwd=$workingDir, cmd=$shellCommand")
        Timber.tag(TAG).i("Using compile_commands.json from: $compileCommandsDir")

        stopping = false
        process = linuxEnvironment.startInteractive(
            command = listOf("/bin/sh", "-c", shellCommand),
            workDir = workingDir,
        )

        val p = process ?: return
        stderrThread = thread(name = "clangd-stderr", isDaemon = true) {
            runCatching {
                p.stderr.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (stopping) break
                        if (line.isNotBlank()) {
                            Timber.tag(TAG).d("stderr: $line")
                        }
                    }
                }
            }.onFailure { e ->
                if (!stopping) {
                    Timber.tag(TAG).d("stderr reader stopped: ${e.message}")
                }
            }
        }
    }

    private fun buildClangdCommand(): String {
        val clangdExe = toolchainPathResolver.getClangd()
        val compileCommandsDirGuest = linuxEnvironment.toGuestPath(compileCommandsDir)

        return buildString {
            append("exec ")
            append(clangdExe)
            append(" --compile-commands-dir=")
            append(shellEscape(compileCommandsDirGuest))
            append(clangdSettings.buildCommandArgs())
        }
    }

    private fun shellEscape(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    override val inputStream: InputStream
        get() = requireNotNull(process) { "clangd process not started" }.stdout

    override val outputStream: OutputStream
        get() = requireNotNull(process) { "clangd process not started" }.stdin

    override fun close() {
        stopping = true
        runCatching { stderrThread?.interrupt() }
        stderrThread = null
        runCatching { process?.destroy() }
        process = null
    }
}
