package com.wuxianggujun.tinaide.ui.runtime

import java.io.BufferedReader
import java.util.concurrent.TimeUnit
import timber.log.Timber

/**
 * Collects the tail of a graphical runtime process's own logcat output.
 *
 * 图形宿主进程结束后会立即被 kill，主进程来不及读取它的运行输出；
 * 因此在失败时（进程还活着）同步抓一次 `logcat --pid=<self>`，把用户程序和 SDL 的诊断信息带进错误对话框。
 */
internal object NativeRuntimeOutputCapture {
    private const val TAG = "NativeRuntimeOutput"
    private const val MAX_LINES = 200
    private const val LOGCAT_TIMEOUT_MS = 2_000L

    private val defaultTags = listOf(
        "TINA_USER_OUTPUT",
        "SDL",
        "System.out",
        "System.err",
    )

    /**
     * @return 最近 [maxLines] 行输出；没有输出或 logcat 不可用时返回空串。
     */
    fun capture(
        pid: Int,
        maxLines: Int = MAX_LINES,
        tags: List<String> = defaultTags,
    ): String {
        if (pid <= 0) return ""

        val command = buildList {
            add("logcat")
            add("-d")
            add("--pid=$pid")
            add("-v")
            add("brief")
            add("-t")
            add(maxLines.toString())
            add("-s")
            addAll(tags.map { "$it:*" })
        }

        val process = runCatching {
            ProcessBuilder(command).redirectErrorStream(true).start()
        }.onFailure { error ->
            Timber.tag(TAG).w(error, "Failed to start logcat for runtime output capture")
        }.getOrNull() ?: return ""

        return try {
            val output = process.inputStream.bufferedReader().use(BufferedReader::readText)
            if (!process.waitFor(LOGCAT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroy()
            }
            output.trim()
        } catch (error: Exception) {
            Timber.tag(TAG).w(error, "Failed to read runtime output")
            ""
        } finally {
            if (process.isAlive) process.destroy()
        }
    }
}
