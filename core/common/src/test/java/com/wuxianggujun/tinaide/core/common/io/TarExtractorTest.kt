package com.wuxianggujun.tinaide.core.common.io

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.File
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TarExtractorTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `extract skips root directory placeholder and extracts descendants`() {
        val script = "#!/bin/sh\necho ok\n".toByteArray()
        val archive = createTarWithRootDirectoryPlaceholder(script)
        val targetDir = tempFolder.newFolder("rootfs")

        TarExtractor.extract(
            input = archive.inputStream(),
            targetDir = targetDir,
            compressionType = TarExtractor.CompressionType.NONE,
            symlinkPolicy = TarExtractor.SymlinkPolicy.REJECT_LINKS,
            limits = TEST_LIMITS,
            archiveSizeBytes = archive.size.toLong(),
        )

        assertThat(File(targetDir, "bin/sh").readBytes()).isEqualTo(script)
    }

    private fun createTarWithRootDirectoryPlaceholder(script: ByteArray): ByteArray =
        ByteArrayOutputStream().use { archive ->
            TarArchiveOutputStream(archive).use { tar ->
                tar.putArchiveEntry(TarArchiveEntry("./"))
                tar.closeArchiveEntry()

                tar.putArchiveEntry(TarArchiveEntry("./bin/"))
                tar.closeArchiveEntry()

                val scriptEntry = TarArchiveEntry("./bin/sh").apply {
                    size = script.size.toLong()
                    mode = 0b111_101_101
                }
                tar.putArchiveEntry(scriptEntry)
                tar.write(script)
                tar.closeArchiveEntry()
            }
            archive.toByteArray()
        }

    private companion object {
        val TEST_LIMITS = ArchiveExtractionLimits(
            maxArchiveBytes = 1024 * 1024,
            maxExpandedBytes = 1024,
            maxEntryBytes = 1024,
            maxEntryCount = 3,
            maxCompressionRatio = 100,
        )
    }
}
