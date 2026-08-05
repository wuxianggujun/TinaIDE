package com.wuxianggujun.tinaide.ui.runtime

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.Test

class NativeRuntimeLibraryIndexTest {

    @Test
    fun `index resolves exact versioned library name`() {
        val tempRoot = Files.createTempDirectory("native-runtime-index-test").toFile()
        try {
            val runtimeDir = File(tempRoot, "runtime").apply { mkdirs() }
            val versionedLibrary = File(runtimeDir, "libsample.so.2").apply { writeText("sample") }

            val index = buildNativeRuntimeLibraryIndex(listOf(runtimeDir))

            assertThat(index["libsample.so.2"]).isEqualTo(versionedLibrary)
            assertThat(resolveNativeRuntimeLibrary(index, "libsample.so.2")).isEqualTo(versionedLibrary)
            assertThat(resolveNativeRuntimeLibrary(index, "libsample.so")).isNull()
            assertThat(resolveNativeRuntimeLibrary(index, "libsample.so.9")).isNull()
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `versioned request may fall back to real unversioned library`() {
        val tempRoot = Files.createTempDirectory("native-runtime-index-test").toFile()
        try {
            val runtimeDir = File(tempRoot, "runtime").apply { mkdirs() }
            val unversionedLibrary = File(runtimeDir, "libsample.so").apply { writeText("sample") }

            val index = buildNativeRuntimeLibraryIndex(listOf(runtimeDir))

            assertThat(resolveNativeRuntimeLibrary(index, "libsample.so.9"))
                .isEqualTo(unversionedLibrary)
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
