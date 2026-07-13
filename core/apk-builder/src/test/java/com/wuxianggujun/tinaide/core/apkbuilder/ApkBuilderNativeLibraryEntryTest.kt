package com.wuxianggujun.tinaide.core.apkbuilder

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.Test

class ApkBuilderNativeLibraryEntryTest {
    @Test
    fun putNativeLibraryEntry_shouldRejectDifferentLibrariesWithSameApkPath() {
        val directory = Files.createTempDirectory("apk-native-entry-conflict-").toFile()
        try {
            val first = File(directory, "first/libsame.so").apply {
                parentFile?.mkdirs()
                writeText("first")
            }
            val second = File(directory, "second/libsame.so").apply {
                parentFile?.mkdirs()
                writeText("second")
            }
            val entries = linkedMapOf<String, File>()
            val entryName = "lib/arm64-v8a/libsame.so"

            assertThat(ApkBuilder.putNativeLibraryEntry(entries, entryName, first)).isNull()

            assertThat(ApkBuilder.putNativeLibraryEntry(entries, entryName, second)).isEqualTo(first)
            assertThat(entries[entryName]).isEqualTo(first)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun putNativeLibraryEntry_shouldDeduplicateIdenticalContent() {
        val directory = Files.createTempDirectory("apk-native-entry-dedup-").toFile()
        try {
            val first = File(directory, "first/libsame.so").apply {
                parentFile?.mkdirs()
                writeText("same")
            }
            val second = File(directory, "second/libsame.so").apply {
                parentFile?.mkdirs()
                writeText("same")
            }
            val entries = linkedMapOf<String, File>()
            val entryName = "lib/arm64-v8a/libsame.so"

            ApkBuilder.putNativeLibraryEntry(entries, entryName, first)

            assertThat(ApkBuilder.putNativeLibraryEntry(entries, entryName, second)).isNull()
            assertThat(entries).hasSize(1)
        } finally {
            directory.deleteRecursively()
        }
    }
}
