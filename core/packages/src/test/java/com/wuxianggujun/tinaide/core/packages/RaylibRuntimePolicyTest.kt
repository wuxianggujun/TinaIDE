package com.wuxianggujun.tinaide.core.packages

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RaylibRuntimePolicyTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `legacy raylib and missing libraries are rejected without a compatibility fallback`() {
        val library = temporaryFolder.newFile("libraylib.so")
        library.writeBytes(ELF_MAGIC + "PLATFORM: ANDROID: Initialized successfully".toByteArray())
        assertThat(RaylibRuntimePolicy.isSupportedRuntimeLibrary(library)).isFalse()
        assertThat(RaylibRuntimePolicy.isSupportedRuntimeLibrary(File(temporaryFolder.root, "libraylib.so.6"))).isFalse()
    }

    @Test
    fun `r3 marker is found even across read buffer boundaries`() {
        val library = temporaryFolder.newFile("libraylib.so")
        library.writeBytes(
            ELF_MAGIC + ByteArray(16 * 1024 - 10) +
                RaylibRuntimePolicy.LIFECYCLE_MARKER.toByteArray(Charsets.US_ASCII),
        )
        assertThat(RaylibRuntimePolicy.isSupportedRuntimeLibrary(library)).isTrue()
    }

    @Test
    fun `other engines are not mistaken for raylib`() {
        val library = temporaryFolder.newFile("libSDL3.so")
        assertThat(RaylibRuntimePolicy.isSupportedRuntimeLibrary(library)).isTrue()
    }

    @Test
    fun `metadata revision cannot disguise an old binary`() {
        val directory = temporaryFolder.newFolder("raylib")
        val library = File(directory, "lib/arm64-v8a/libraylib.so")
        library.parentFile!!.mkdirs()
        library.writeBytes(ELF_MAGIC)
        val metadata = InstalledPackageMetadata("raylib", "raylib", "6.0", packageRevision = 3, abis = listOf("arm64-v8a"))
        assertThat(RaylibRuntimePolicy.isSupportedPackage(directory, metadata)).isFalse()

        library.appendBytes(RaylibRuntimePolicy.LIFECYCLE_MARKER.toByteArray(Charsets.US_ASCII))
        assertThat(RaylibRuntimePolicy.isSupportedPackage(directory, metadata)).isTrue()
        assertThat(RaylibRuntimePolicy.isSupportedPackage(directory, metadata.copy(packageRevision = 2))).isFalse()
        assertThat(RaylibRuntimePolicy.isSupportedPackage(directory, metadata.copy(abis = listOf("../escape")))).isFalse()
    }

    private companion object {
        val ELF_MAGIC = byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
    }
}
