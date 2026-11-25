package com.wuxianggujun.tinaide.core.packages

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VcpkgInstalledStatusTest {
    @get:Rule val temporary = TemporaryFolder()
    private val status get() = File(temporary.root, "vcpkg/status")

    @Test fun writerAndReaderPreserveUtf8VersionsAndSortTheSharedIndex() {
        VcpkgInstalledStatus.write(status, "arm64-android", mapOf("zlib" to "1.3", "fmt" to "2🙂"))
        assertThat(VcpkgInstalledStatus.read(status, "arm64-android") {}).containsExactly("fmt", "2🙂", "zlib", "1.3").inOrder()
        assertThat(status.readText(Charsets.UTF_8)).startsWith("Package: fmt\n")
        assertThat(VcpkgInstalledStatus.read(status, "x64-android") {}).isEmpty()
    }

    @Test fun featureIncompleteAndForeignArchitectureRecordsCannotAppearInstalled() {
        status.parentFile!!.mkdirs()
        status.writeText("""
            Package: fmt
            Architecture: arm64-android
            Version: 1
            Status: install ok installed

            Package: fmt
            Feature: extras
            Architecture: arm64-android
            Version: incorrect
            Status: install ok installed

            Package: incomplete
            Architecture: arm64-android
            Status: purge ok not-installed

            Package: foreign
            Architecture: x64-android
            Status: install ok installed
        """.trimIndent(), Charsets.UTF_8)
        assertThat(VcpkgInstalledStatus.read(status, "arm64-android") {}).containsExactly("fmt", "1")
    }

    @Test fun oversizedAndMalformedUtf8MetadataAreRejectedBeforePublication() {
        status.parentFile!!.mkdirs()
        RandomAccessFile(status, "rw").use { it.setLength(VcpkgRepository.MAX_STATUS_BYTES + 1L) }
        assertThat(runCatching { VcpkgInstalledStatus.read(status, "arm64-android") {} }.isFailure).isTrue()
        status.writeBytes(byteArrayOf(0x80.toByte()))
        assertThat(runCatching { VcpkgInstalledStatus.read(status, "arm64-android") {} }.isFailure).isTrue()
        assertThat(runCatching { VcpkgInstalledStatus.write(status, "arm64-android", mapOf("fmt" to "1\nPackage: injected")) }.isFailure).isTrue()
    }

    @Test fun metadataIterationPropagatesCancellation() {
        VcpkgInstalledStatus.write(status, "arm64-android", mapOf("fmt" to "1"))
        val failure = runCatching {
            VcpkgInstalledStatus.read(status, "arm64-android") { throw CancellationException("fixture") }
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(CancellationException::class.java)
    }
}
