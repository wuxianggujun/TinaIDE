package com.wuxianggujun.tinaide.core.crash

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Test

class CrashReportFilesTest {
    @Test
    fun export_shouldKeepCompleteLogWhenPreviewIsTruncated() {
        val root = createTempDirectory("crash-report").toFile()
        try {
            val logs = File(root, "tombstones").apply { mkdirs() }
            val original = File(logs, "tombstone.txt").apply {
                writeText("中文🙂".repeat(40_000) + "END_OF_ORIGINAL", Charsets.UTF_8)
            }
            val preview = CrashReportFiles.readPreview(original)
            assertThat(preview.length).isAtMost(CrashReportFiles.MAX_PREVIEW_CHARS)
            assertThat(preview).doesNotContain("END_OF_ORIGINAL")
            val exported = CrashReportFiles.export(logs, original.name, preview, File(root, "export/report.txt"))
            assertThat(exported.readBytes()).isEqualTo(original.readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun resolveOriginal_shouldRejectPathsOutsideLogDirectory() {
        val root = createTempDirectory("crash-report-path").toFile()
        try {
            listOf("../private.txt", "..\\private.txt", "/private.txt", "..").forEach { name ->
                assertThat(runCatching { CrashReportFiles.resolveOriginal(root, name) }.exceptionOrNull())
                    .isInstanceOf(IllegalArgumentException::class.java)
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
