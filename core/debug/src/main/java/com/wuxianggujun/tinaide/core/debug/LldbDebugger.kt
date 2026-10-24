package com.wuxianggujun.tinaide.core.debug

import android.content.Context
import com.wuxianggujun.tinaide.core.proot.InteractiveProcess
import com.wuxianggujun.tinaide.core.proot.PRootManager
import com.wuxianggujun.tinaide.core.proot.ToolchainPathResolver
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** LLDB dialogue shared by native and PRoot launchers. A timed-out dialogue is never reused. */
class LldbDebugger internal constructor(
    private val executable: String,
    private val launchProcess: suspend (List<String>, String, Map<String, String>) -> InteractiveProcess,
) : Closeable {
    constructor(context: Context, prootManager: PRootManager) : this(
        ToolchainPathResolver(context).getLldb(),
        { command, directory, environment ->
            prootManager.startInteractive(command = command, workDir = directory, extraEnv = environment)
        }
    )

    data class OutputChunk(val text: String, val isStderr: Boolean)

    private class Session(val process: InteractiveProcess) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val startup = CompletableDeferred<Unit>()
        val decoder = LldbPromptDecoder()
        var response: CompletableDeferred<String>? = null
        val captured = StringBuilder()
    }

    private val lock = Any()
    private val commands = Mutex()
    private var active: Session? = null
    private val _output = MutableSharedFlow<OutputChunk>(extraBufferCapacity = 256)
    val output = _output.asSharedFlow()
    private val _failure = MutableStateFlow<Throwable?>(null)
    val failure = _failure.asStateFlow()

    suspend fun start(
        programPath: String,
        arguments: List<String> = emptyList(),
        workingDirectory: String,
        environment: Map<String, String> = emptyMap(),
    ): Result<Unit> = withContext(Dispatchers.IO) {
        commands.withLock {
            var started: Session? = null
            try {
                check(synchronized(lock) { active == null }) { "Debugger already started" }
                val command = listOf(executable, "--no-use-colors", "--no-lldbinit", "--file", programPath, "--") + arguments
                val process = launchProcess(command, workingDirectory, environment.filterKeys { ENV_NAME.matches(it) })
                val session = Session(process)
                started = session
                synchronized(lock) { active = session; _failure.value = null }
                session.scope.launch { pump(session, process.stdout, false) }
                session.scope.launch { pump(session, process.stderr, true) }
                withTimeout(15_000) { session.startup.await() }
                val token = "__tina_lldb_${UUID.randomUUID()}__"
                synchronized(lock) { session.decoder.prompt = token }
                sendUnlocked(session, "settings set prompt \"$token\"", 10_000)
                sendUnlocked(session, "settings set auto-confirm true", 10_000)
                sendUnlocked(session, "settings set stop-line-count-before 0", 10_000)
                sendUnlocked(session, "settings set stop-line-count-after 0", 10_000)
                Result.success(Unit)
            } catch (error: Exception) {
                started?.let { terminate(it, error) }
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                Result.failure(error)
            }
        }
    }

    suspend fun sendCommand(command: String, timeoutMs: Long = 10_000): Result<String> = withContext(Dispatchers.IO) {
        commands.withLock {
            val session = synchronized(lock) { active }
                ?: return@withLock Result.failure(IOException("Debugger not running"))
            try {
                Result.success(sendUnlocked(session, command, timeoutMs))
            } catch (error: Exception) {
                terminate(session, error)
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                Result.failure(error)
            }
        }
    }

    // Stop must not wait behind a run/continue command holding the command mutex.
    suspend fun stop(): Result<Unit> = withContext(Dispatchers.IO) { close(); Result.success(Unit) }

    override fun close() {
        synchronized(lock) { active }?.let { terminate(it, IOException("Debugger closed"), reportFailure = false) }
    }

    private suspend fun sendUnlocked(session: Session, command: String, timeoutMs: Long): String {
        require('\n' !in command && '\r' !in command && '\u0000' !in command) { "Expected one LLDB command" }
        val response = CompletableDeferred<String>()
        synchronized(lock) {
            check(active === session && session.process.isRunning()) { "Debugger not running" }
            session.captured.setLength(0)
            session.response = response
        }
        session.process.stdin.write((command + "\n").toByteArray(Charsets.UTF_8))
        session.process.stdin.flush()
        return withTimeout(timeoutMs) { response.await() }
    }

    private suspend fun pump(session: Session, stream: InputStream, stderr: Boolean) {
        try {
            // Reader retains partial UTF-8 sequences. readLine would hang on LLDB's newline-free prompt.
            InputStreamReader(stream, Charsets.UTF_8).use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    val chunks = mutableListOf<OutputChunk>()
                    synchronized(lock) {
                        if (active !== session) return
                        fun append(text: String) {
                            chunks += OutputChunk(text, stderr)
                            if (session.response != null) {
                                if (session.captured.length + text.length > MAX_RESPONSE_CHARS) throw IOException("LLDB response exceeds limit")
                                session.captured.append(text)
                            }
                        }
                        val text = String(buffer, 0, count)
                        if (stderr) append(text) else session.decoder.accept(text, ::append) {
                            session.startup.complete(Unit)
                            session.response?.complete(session.captured.toString())
                            session.response = null
                            session.captured.setLength(0)
                        }
                    }
                    chunks.forEach { _output.emit(it) }
                }
            }
            if (!stderr) terminate(session, IOException("LLDB stdout closed"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            terminate(session, error)
        }
    }

    private fun terminate(session: Session, error: Throwable, reportFailure: Boolean = true) {
        synchronized(lock) {
            if (active !== session) return
            active = null
            session.startup.completeExceptionally(error)
            session.response?.completeExceptionally(error)
            session.response = null
            session.captured.setLength(0)
            if (reportFailure) _failure.value = error
        }
        try { session.process.destroy() } finally { session.scope.cancel() }
    }

    companion object {
        private val ENV_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private const val MAX_RESPONSE_CHARS = 4 * 1024 * 1024
    }
}
