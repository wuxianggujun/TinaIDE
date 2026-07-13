package com.wuxianggujun.tinaide.core.compile

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.Test

class RunStagingCleanerTest {
    @Test
    fun cleanup_removesStaleFifoAndKeepsNewestBoundedEntries() {
        val directory = Files.createTempDirectory("tina-run-staging").toFile()
        try {
            val now = 10_000L
            val oldest = createEntry(directory, "main.old", 1_000L)
            val newer = createEntry(directory, "main.new", 8_000L)
            val keep = createEntry(directory, "main.keep", 500L)
            val staleFifo = createEntry(directory, "main.stderr.123", 1_000L)
            val freshFifo = createEntry(directory, "main.stderr.456", 9_500L)

            RunStagingCleaner.cleanup(
                stageDir = directory,
                keepFileName = keep.name,
                nowMillis = now,
                maxStagedFiles = 2,
                staleFifoAgeMillis = 2_000L,
            )

            assertThat(keep.exists()).isTrue()
            assertThat(newer.exists()).isTrue()
            assertThat(oldest.exists()).isFalse()
            assertThat(staleFifo.exists()).isFalse()
            assertThat(freshFifo.exists()).isTrue()
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun createEntry(directory: File, name: String, modifiedAt: Long): File =
        File(directory, name).apply {
            writeText(name)
            assertThat(setLastModified(modifiedAt)).isTrue()
        }
}
