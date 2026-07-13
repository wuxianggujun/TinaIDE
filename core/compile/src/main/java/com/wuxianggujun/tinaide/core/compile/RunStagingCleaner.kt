package com.wuxianggujun.tinaide.core.compile

import java.io.File
import timber.log.Timber

/** 对 app 私有 run-bin 做有界清理，防止 staged ELF 和异常退出遗留的 FIFO 无限累积。 */
internal object RunStagingCleaner {
    private val stderrFifoName = Regex(".+\\.stderr\\.\\d+")

    fun cleanup(
        stageDir: File,
        keepFileName: String,
        nowMillis: Long = System.currentTimeMillis(),
        maxStagedFiles: Int = DEFAULT_MAX_STAGED_FILES,
        staleFifoAgeMillis: Long = DEFAULT_STALE_FIFO_AGE_MILLIS,
    ) {
        if (!stageDir.isDirectory || maxStagedFiles < 1) return
        val entries = stageDir.listFiles()?.toList().orEmpty()

        entries.asSequence()
            .filter { stderrFifoName.matches(it.name) }
            .filter { nowMillis - it.lastModified() >= staleFifoAgeMillis }
            .forEach(::deleteQuietly)

        val oldStagedFiles = entries.asSequence()
            .filterNot { it.name == keepFileName || stderrFifoName.matches(it.name) }
            .sortedByDescending(File::lastModified)
            .drop((maxStagedFiles - 1).coerceAtLeast(0))
        oldStagedFiles.forEach(::deleteQuietly)
    }

    private fun deleteQuietly(file: File) {
        runCatching { file.delete() }
            .onFailure { Timber.tag(TAG).d(it, "Failed to clean run staging entry: %s", file.name) }
    }

    private const val TAG = "RunStagingCleaner"
    private const val DEFAULT_MAX_STAGED_FILES = 24
    private const val DEFAULT_STALE_FIFO_AGE_MILLIS = 60 * 60 * 1_000L
}
