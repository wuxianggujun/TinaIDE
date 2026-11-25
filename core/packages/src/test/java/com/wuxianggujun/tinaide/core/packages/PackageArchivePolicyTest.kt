package com.wuxianggujun.tinaide.core.packages

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PackageArchivePolicyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun cancellationInterruptsOneLargeEntryBeforeItsProgressCallback() {
        val payload = ByteArray(2 * 1024 * 1024).also { Random(42).nextBytes(it) }
        val archive = temporary.newFile("large.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("large.bin"))
            zip.write(payload)
            zip.closeEntry()
        }
        val output = temporary.newFolder("output")
        val partial = File(output, "large.bin")
        var reportedProgress = false
        assertThrows(CancellationException::class.java) {
            PackageArchivePolicy.extractZip(archive, output, ensureActive = {
                if (partial.length() >= 32 * 1024) throw CancellationException("User cancelled")
            }) { reportedProgress = true }
        }
        assertThat(partial.length()).isAtLeast(32 * 1024L)
        assertThat(partial.length()).isLessThan(payload.size.toLong())
        assertThat(reportedProgress).isFalse()
    }

    @Test fun alreadyCancelledExtractionDoesNotCreateOutputFiles() {
        val output = temporary.newFolder("output")
        assertThrows(CancellationException::class.java) {
            PackageArchivePolicy.extractZip(File(temporary.root, "not-even-opened.zip"), output,
                ensureActive = { throw CancellationException("Already cancelled") })
        }
        assertThat(output.listFiles().orEmpty()).isEmpty()
    }
}
