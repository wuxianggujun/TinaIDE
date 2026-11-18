package com.wuxianggujun.tinaide.core.logging

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LogArchiveWriterTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun sameSecondExportsCannotOverwriteEachOthersConsentProfile() = runTest {
        val directory = File(temporary.root, "new-cache")
        val archives = listOf("automatic-host-only", "manual-user-runtime").map { profile ->
            async {
                writeLogArchive(directory, "tinaide_logs_same-second.zip") { zip ->
                    zip.putNextEntry(ZipEntry("profile.txt"))
                    zip.write(profile.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }.awaitAll()
        assertThat(archives).containsNoDuplicates()
        val contents = archives.map { file ->
            ZipFile(file).use { zip -> zip.getInputStream(zip.getEntry("profile.txt")).use { it.readBytes().toString(Charsets.UTF_8) } }
        }
        assertThat(contents).containsExactly("automatic-host-only", "manual-user-runtime").inOrder()
    }

    @Test fun failedAndCancelledExportsLeaveNoUnownedFile() = runTest {
        val failure = runCatching {
            writeLogArchive(temporary.root, "fixture.zip") { throw IOException("fixture write failure") }
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(temporary.root.listFiles().orEmpty()).isEmpty()
        val task = async {
            val caller = coroutineContext.job
            writeLogArchive(temporary.root, "fixture.zip") { caller.cancel(CancellationException("fixture cancelled")) }
        }
        assertThat(runCatching { task.await() }.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        assertThat(temporary.root.listFiles().orEmpty()).isEmpty()
    }
}
