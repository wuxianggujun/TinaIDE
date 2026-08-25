package com.wuxianggujun.tinaide.core.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CrashLogPrivacyClassifierTest {
    private val packageName = "com.example.tinaide"

    @Test
    fun `isHostAppProcess detects only package main process`() {
        assertThat(CrashLogPrivacyClassifier.isHostAppProcess(packageName, packageName)).isTrue()
        assertThat(CrashLogPrivacyClassifier.isHostAppProcess(packageName, "$packageName:gui")).isFalse()
        assertThat(CrashLogPrivacyClassifier.isHostAppProcess(packageName, "$packageName:sdl")).isFalse()
    }

    @Test
    fun `isUserRuntimeProcess detects isolated native containers`() {
        assertThat(CrashLogPrivacyClassifier.isUserRuntimeProcess(packageName, "$packageName:gui")).isTrue()
        assertThat(CrashLogPrivacyClassifier.isUserRuntimeProcess(packageName, "$packageName:sdl")).isTrue()
        assertThat(CrashLogPrivacyClassifier.isUserRuntimeProcess(packageName, "$packageName:sdl2")).isTrue()
        assertThat(CrashLogPrivacyClassifier.isUserRuntimeProcess(packageName, packageName)).isFalse()
        assertThat(CrashLogPrivacyClassifier.isUserRuntimeProcess(packageName, "$packageName:toolchain")).isFalse()
    }

    @Test
    fun `shouldUploadCrashForProcess allows host app process only`() {
        assertThat(CrashLogPrivacyClassifier.shouldUploadCrashForProcess(packageName, packageName)).isTrue()
        assertThat(CrashLogPrivacyClassifier.shouldUploadCrashForProcess(packageName, "$packageName:gui")).isFalse()
        assertThat(CrashLogPrivacyClassifier.shouldUploadCrashForProcess(packageName, "$packageName:sdl")).isFalse()
    }

    @Test
    fun `isUserRuntimeCrash detects isolated gui and sdl process`() {
        assertThat(
            CrashLogPrivacyClassifier.isUserRuntimeCrash(
                packageName,
                ">>> com.example.tinaide:gui <<<"
            )
        ).isTrue()
        assertThat(
            CrashLogPrivacyClassifier.isUserRuntimeCrash(
                packageName,
                ">>> com.example.tinaide:sdl <<<"
            )
        ).isTrue()
        assertThat(
            CrashLogPrivacyClassifier.isUserRuntimeCrash(
                packageName,
                ">>> com.example.tinaide:sdl2 <<<"
            )
        ).isTrue()
    }

    @Test
    fun `isUserRuntimeCrash detects normal native run bin output`() {
        val tombstoneHeader = """
            Cmdline: /system/bin/linker64 /data/user/0/com.example.tinaide/files/run-bin/main.a1b2c3
            signal 11 (SIGSEGV)
        """.trimIndent()

        assertThat(CrashLogPrivacyClassifier.isUserRuntimeCrash(packageName, tombstoneHeader)).isTrue()
    }

    @Test
    fun `isUserRuntimeCrash keeps main app crash report uploadable`() {
        val tombstoneHeader = """
            >>> com.example.tinaide <<<
            signal 11 (SIGSEGV)
        """.trimIndent()

        assertThat(CrashLogPrivacyClassifier.isUserRuntimeCrash(packageName, tombstoneHeader)).isFalse()
    }
}
