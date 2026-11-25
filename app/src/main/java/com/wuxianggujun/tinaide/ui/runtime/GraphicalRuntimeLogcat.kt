package com.wuxianggujun.tinaide.ui.runtime

import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

private const val LOGCAT_INITIAL_LINE_COUNT = 200
private const val LOG_BATCH_SIZE = 64
private const val LOG_BATCH_INTERVAL_MS = 50L
private val LOGCAT_BRIEF_LINE = Regex("^([VDIWEF])/([^\\r\\n]*?)\\(\\s*\\d+\\): ?(.*)$")

/** Filter at the source so framework noise cannot consume the program log's bounded buffer. */
internal fun graphicalRuntimeLogcatCommand(processId: Int, showAllProcessLogs: Boolean = false): List<String> =
    listOf("logcat", "--pid=$processId", "-v", "brief", "-T", LOGCAT_INITIAL_LINE_COUNT.toString()) +
        GraphicalRuntimeLogPolicy.logcatFilters(showAllProcessLogs)

internal suspend fun captureGraphicalRuntimeLogcat(
    failureMessage: String,
    append: (List<GraphicalRuntimeLogEntry>) -> Unit,
    showAllProcessLogs: Boolean = false,
    startProcess: (List<String>) -> Process = { ProcessBuilder(it).redirectErrorStream(true).start() },
) = withContext(Dispatchers.IO) {
    val process = try {
        startProcess(graphicalRuntimeLogcatCommand(android.os.Process.myPid(), showAllProcessLogs))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Timber.tag("GraphicalRuntimeOverlay").w(error, "Unable to start graphical log capture")
        withContext(Dispatchers.Main.immediate) { append(listOf(GraphicalRuntimeLogEntry('E', failureMessage))) }
        return@withContext
    }

    val pending = Channel<GraphicalRuntimeLogEntry>(LOG_BATCH_SIZE)
    val consumer = launch(Dispatchers.Main.immediate) {
        for (first in pending) {
            val batch = ArrayList<GraphicalRuntimeLogEntry>(LOG_BATCH_SIZE)
            batch.add(first)
            delay(LOG_BATCH_INTERVAL_MS)
            while (batch.size < LOG_BATCH_SIZE) batch.add(pending.tryReceive().getOrNull() ?: break)
            append(batch)
        }
    }
    val cancellationCloser = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            // Unblock readLine when the Activity's composition is disposed.
            process.destroy()
        }
    }
    try {
        withContext(Dispatchers.IO) {
            val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
            var level = 'I'
            var tag = ""
            var visible = true
            while (isActive) {
                val line = reader.readLine() ?: break
                if (line.isBlank() || line.startsWith("-----")) continue
                val header = LOGCAT_BRIEF_LINE.matchEntire(line)
                if (header != null) {
                    level = header.groupValues[1].single()
                    tag = header.groupValues[2].trim()
                    visible = GraphicalRuntimeLogPolicy.accepts(tag, level, showAllProcessLogs)
                }
                if (!visible) continue
                val message = header?.groupValues?.get(3) ?: line
                val displayMessage = if (showAllProcessLogs && tag.isNotEmpty()) "[$tag] $message" else message
                pending.send(GraphicalRuntimeLogEntry(level, displayMessage.take(GRAPHICAL_LOG_MAX_LINE_CHARS + 1)))
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        if (isActive) {
            Timber.tag("GraphicalRuntimeOverlay").w(error, "Graphical log capture failed")
            pending.send(GraphicalRuntimeLogEntry('E', failureMessage))
        }
    } finally {
        pending.close()
        cancellationCloser.cancel()
        process.destroy()
        listOf(process.inputStream, process.errorStream, process.outputStream).forEach { stream ->
            runCatching { stream.close() }.onFailure { error ->
                Timber.tag("GraphicalRuntimeOverlay").d(error, "Log capture stream already closed")
            }
        }
    }
    consumer.join()
}
