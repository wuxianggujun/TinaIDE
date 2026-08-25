package com.wuxianggujun.tinaide.core.compile

import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import timber.log.Timber

/**
 * Runs short-lived native build helper processes with deterministic timeout and stream cleanup.
 */
internal object BuildProcessRunner {
    private const val TAG = "BuildProcessRunner"
    private const val DEFAULT_FORCE_KILL_GRACE_MS = 2_000L
    private const val DEFAULT_READER_JOIN_TIMEOUT_MS = 2_000L

    data class Result(
        val exitCode: Int,
        val output: String,
        val timedOut: Boolean,
        val durationMs: Long,
    )

    suspend fun run(
        processBuilder: ProcessBuilder,
        commandLabel: String,
        timeoutMs: Long,
        stdin: String? = null,
        onOutputLine: ((String) -> Unit)? = null,
        forceKillGraceMs: Long = DEFAULT_FORCE_KILL_GRACE_MS,
        readerJoinTimeoutMs: Long = DEFAULT_READER_JOIN_TIMEOUT_MS,
    ): Result = runInterruptible(Dispatchers.IO) {
        runBlocking(
            processBuilder = processBuilder,
            commandLabel = commandLabel,
            timeoutMs = timeoutMs,
            stdin = stdin,
            onOutputLine = onOutputLine,
            forceKillGraceMs = forceKillGraceMs,
            readerJoinTimeoutMs = readerJoinTimeoutMs,
        )
    }

    private fun runBlocking(
        processBuilder: ProcessBuilder,
        commandLabel: String,
        timeoutMs: Long,
        stdin: String?,
        onOutputLine: ((String) -> Unit)?,
        forceKillGraceMs: Long,
        readerJoinTimeoutMs: Long,
    ): Result {
        var process: Process? = null
        val startedAt = System.currentTimeMillis()
        val output = StringBuilder()
        val outputLock = Any()
        val readerDone = CountDownLatch(1)
        val readerError = AtomicReference<Throwable?>(null)
        val writerDone = CountDownLatch(if (stdin == null) 0 else 1)
        val writerError = AtomicReference<Throwable?>(null)

        try {
            process = processBuilder.start()
            val runningProcess = process

            if (stdin == null) {
                closeQuietly(runningProcess.outputStream)
            } else {
                thread(
                    name = "BuildProcessRunner-stdin",
                    isDaemon = true,
                ) {
                    try {
                        runningProcess.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                            writer.write(stdin)
                        }
                    } catch (t: Throwable) {
                        writerError.set(t)
                    } finally {
                        writerDone.countDown()
                    }
                }
            }

            thread(
                name = "BuildProcessRunner-output",
                isDaemon = true,
            ) {
                try {
                    runningProcess.inputStream.bufferedReader().use { reader ->
                        reader.lineSequence().forEach { line ->
                            synchronized(outputLock) {
                                output.appendLine(line)
                            }
                            onOutputLine?.invoke(line)
                        }
                    }
                } catch (t: Throwable) {
                    readerError.set(t)
                } finally {
                    readerDone.countDown()
                }
            }

            val finished = runningProcess.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            val exitCode = if (finished) {
                runningProcess.exitValue()
            } else {
                terminate(runningProcess, forceKillGraceMs)
                -1
            }

            closeQuietly(runningProcess.outputStream)
            if (!readerDone.await(readerJoinTimeoutMs, TimeUnit.MILLISECONDS)) {
                Timber.tag(TAG).w("Timed out waiting for output reader: %s", commandLabel)
                BuildDiagnosticsLog.w {
                    "build process output reader still active command=$commandLabel timeoutMs=$readerJoinTimeoutMs"
                }
                closeQuietly(runningProcess.inputStream)
            }
            readerError.get()?.let { error ->
                Timber.tag(TAG).w(error, "Build process output reader failed: %s", commandLabel)
            }
            if (!writerDone.await(readerJoinTimeoutMs, TimeUnit.MILLISECONDS)) {
                Timber.tag(TAG).w("Timed out waiting for stdin writer: %s", commandLabel)
                BuildDiagnosticsLog.w {
                    "build process stdin writer still active command=$commandLabel timeoutMs=$readerJoinTimeoutMs"
                }
            }
            writerError.get()?.let { error ->
                Timber.tag(TAG).w(error, "Build process stdin writer failed: %s", commandLabel)
            }
            closeQuietly(runningProcess.errorStream)

            val durationMs = System.currentTimeMillis() - startedAt
            val result = Result(
                exitCode = exitCode,
                output = synchronized(outputLock) { output.toString() },
                timedOut = !finished,
                durationMs = durationMs,
            )
            BuildDiagnosticsLog.i {
                "build process finished command=$commandLabel exitCode=${result.exitCode} " +
                    "timedOut=${result.timedOut} durationMs=${result.durationMs} outputChars=${result.output.length}"
            }
            return result
        } catch (e: InterruptedException) {
            process?.let { terminate(it, forceKillGraceMs) }
            Thread.currentThread().interrupt()
            throw e
        } finally {
            process?.let { runningProcess ->
                if (runningProcess.isAlive) {
                    terminate(runningProcess, forceKillGraceMs)
                }
                closeProcessStreams(runningProcess)
            }
        }
    }

    private fun terminate(process: Process, forceKillGraceMs: Long) {
        if (!process.isAlive) return
        runCatching { process.destroy() }
        if (runCatching { process.waitFor(forceKillGraceMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)) {
            return
        }
        runCatching { process.destroyForcibly() }
        runCatching { process.waitFor(forceKillGraceMs, TimeUnit.MILLISECONDS) }
    }

    private fun closeProcessStreams(process: Process) {
        closeQuietly(process.outputStream)
        closeQuietly(process.inputStream)
        closeQuietly(process.errorStream)
    }

    private fun closeQuietly(closeable: Closeable?) {
        runCatching { closeable?.close() }
    }
}
