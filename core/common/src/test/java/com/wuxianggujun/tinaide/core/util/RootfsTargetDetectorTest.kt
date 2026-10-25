package com.wuxianggujun.tinaide.core.util

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.Test

class RootfsTargetDetectorTest {

    @Test
    fun `detect resolves GNU linux roots by gnu triple dirs`() {
        // detectRootfsType 以 {arch}-linux-gnu/bits 目录为 GNU 特征
        withRootfs("usr/include/aarch64-linux-gnu/bits") { root ->
            val result = RootfsTargetDetector.detect(root)
            assertThat(result.type).isEqualTo(RootfsTargetDetector.RootfsType.GNU_LINUX)
            assertThat(result.target).isEqualTo("aarch64-linux-gnu")
        }
        withRootfs("usr/include/x86_64-linux-gnu/bits") { root ->
            assertThat(RootfsTargetDetector.detect(root).target).isEqualTo("x86_64-linux-gnu")
        }
    }

    @Test
    fun `detect resolves Android NDK sysroots by android triple dirs`() {
        // detectRootfsType 以 usr/include/android/log.h 为 NDK 特征
        withRootfs("usr/include/aarch64-linux-android", "usr/include/android/log.h") { root ->
            val result = RootfsTargetDetector.detect(root)
            assertThat(result.type).isEqualTo(RootfsTargetDetector.RootfsType.ANDROID_NDK)
            assertThat(result.target).isEqualTo("aarch64-linux-android28")
        }
        withRootfs("usr/include/x86_64-linux-android", "usr/include/android/log.h") { root ->
            val result = RootfsTargetDetector.detect(root)
            assertThat(result.type).isEqualTo(RootfsTargetDetector.RootfsType.ANDROID_NDK)
            assertThat(result.target).isEqualTo("x86_64-linux-android28")
        }
        withRootfs("usr/include/i686-linux-android", "usr/include/android/log.h") { root ->
            assertThat(RootfsTargetDetector.detect(root).target).isEqualTo("i686-linux-android28")
        }
    }

    @Test
    fun `detect falls back to aarch64 for unrecognized layouts`() {
        // UNKNOWN rootfs 保守回落 GNU aarch64 三元组（与实现一致）。
        withRootfs("usr/include") { root ->
            val result = RootfsTargetDetector.detect(root)
            assertThat(result.type).isEqualTo(RootfsTargetDetector.RootfsType.UNKNOWN)
            assertThat(result.target).isEqualTo("aarch64-linux-gnu")
        }
    }

    private fun withRootfs(vararg dirsToCreate: String, block: (File) -> Unit) {
        val root = Files.createTempDirectory("rootfs-detect").toFile()
        try {
            dirsToCreate.forEach { relative ->
                File(root, relative).mkdirs()
            }
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
