package com.wuxianggujun.tinaide.core.common.io

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchiveExtractionLimitsTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val limits = ArchiveExtractionLimits(
        maxArchiveBytes = 1024 * 1024,
        maxExpandedBytes = 1024,
        maxEntryBytes = 768,
        maxEntryCount = 4,
        maxCompressionRatio = 10,
        maxPathDepth = 2,
    )

    @Test
    fun `validator reports normal archive summary`() {
        val archive = createZip("normal.zip", mapOf("a.txt" to ByteArray(32), "b.txt" to ByteArray(16)))

        val summary = ZipArchiveValidator.validate(archive, limits)

        assertThat(summary.fileCount).isEqualTo(2)
        assertThat(summary.declaredExpandedBytes).isEqualTo(48)
    }

    @Test
    fun `validator rejects duplicate normalized path`() {
        val archive = File(tempFolder.root, "duplicate.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("dir/file.txt"))
            zip.write("a".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("dir//file.txt"))
            zip.write("b".toByteArray())
            zip.closeEntry()
        }

        assertThrows(ArchiveLimitException::class.java) {
            ZipArchiveValidator.validate(archive, limits)
        }
    }

    @Test
    fun `budget rejects streamed entry beyond declared boundary`() {
        val budget = ArchiveExtractionBudget(limits.copy(maxEntryBytes = 8, maxCompressionRatio = 100))
        budget.beginEntry("payload.bin")

        assertThrows(ArchiveLimitException::class.java) {
            budget.readEntry(ByteArray(9).inputStream(), "payload.bin")
        }
    }

    @Test
    fun `budget rejects streamed archive with excessive expansion ratio`() {
        val budget = ArchiveExtractionBudget(
            limits = limits.copy(maxExpandedBytes = 1024, maxEntryBytes = 1024, maxCompressionRatio = 10),
            archiveBytes = 10,
        )
        budget.beginEntry("payload.bin")

        assertThrows(ArchiveLimitException::class.java) {
            budget.readEntry(ByteArray(101).inputStream(), "payload.bin")
        }
    }

    @Test
    fun `budget rejects duplicate normalized path`() {
        val budget = ArchiveExtractionBudget(limits)
        budget.beginEntry("dir/file.txt", 1)

        assertThrows(ArchiveLimitException::class.java) {
            budget.beginEntry("dir//file.txt", 1)
        }
    }

    @Test
    fun `budget accepts tar root directory placeholder and counts it`() {
        listOf(".", "./").forEach { rootDirectoryPlaceholder ->
            val budget = ArchiveExtractionBudget(limits.copy(maxEntryCount = 1))

            budget.beginEntry(rootDirectoryPlaceholder, declaredSize = 0, isDirectory = true)

            assertThrows(ArchiveLimitException::class.java) {
                budget.beginEntry("payload.bin", declaredSize = 1)
            }
        }
    }

    @Test
    fun `budget rejects truly empty directory path`() {
        val budget = ArchiveExtractionBudget(limits)

        assertThrows(ArchiveLimitException::class.java) {
            budget.beginEntry("", declaredSize = 0, isDirectory = true)
        }
    }

    @Test
    fun `budget rejects root placeholder used by a non-directory entry`() {
        val budget = ArchiveExtractionBudget(limits)

        assertThrows(ArchiveLimitException::class.java) {
            budget.beginEntry("./", declaredSize = 0, isDirectory = false)
        }
    }

    @Test
    fun `validator rejects entry path beyond depth limit`() {
        val archive = createZip("deep.zip", mapOf("one/two/three.txt" to byteArrayOf(1)))

        assertThrows(ArchiveLimitException::class.java) {
            ZipArchiveValidator.validate(archive, limits)
        }
    }

    @Test
    fun `budget rejects streamed entry path beyond depth limit`() {
        val budget = ArchiveExtractionBudget(limits)

        assertThrows(ArchiveLimitException::class.java) {
            budget.beginEntry("one/two/three.txt", 1)
        }
    }

    private fun createZip(name: String, entries: Map<String, ByteArray>): File =
        File(tempFolder.root, name).also { archive ->
            ZipOutputStream(archive.outputStream()).use { zip ->
                entries.forEach { (entryName, bytes) ->
                    zip.putNextEntry(ZipEntry(entryName))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }
}
