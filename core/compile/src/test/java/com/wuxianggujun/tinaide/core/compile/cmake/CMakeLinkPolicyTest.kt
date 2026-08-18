package com.wuxianggujun.tinaide.core.compile.cmake

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CMakeLinkPolicyTest {

    @Test
    fun `resolveStandardLibraries returns blank when project does not declare ld libs`() {
        val resolved = CMakeLinkPolicy.resolveStandardLibraries("")

        assertThat(resolved).isEmpty()
    }

    @Test
    fun `resolveStandardLibraries normalizes explicit ld libs only`() {
        val resolved = CMakeLinkPolicy.resolveStandardLibraries(
            """
            -lSDL3
            -lEGL
            -lSDL3
            """.trimIndent()
        )

        assertThat(resolved).isEqualTo("-lSDL3 -lEGL")
    }

    @Test
    fun `resolveAndroidStandardLibraries includes android log by default`() {
        val resolved = CMakeLinkPolicy.resolveAndroidStandardLibraries("")

        assertThat(resolved).isEqualTo("-llog")
    }

    @Test
    fun `resolveAndroidStandardLibraries keeps android log before explicit libraries`() {
        val resolved = CMakeLinkPolicy.resolveAndroidStandardLibraries(
            """
            -lSDL3
            -llog
            -lEGL
            """.trimIndent()
        )

        assertThat(resolved).isEqualTo("-llog -lSDL3 -lEGL")
    }

    @Test
    fun `resolveAndroidSharedLinkerFlags adds shell-escaped origin runpath`() {
        val resolved = CMakeLinkPolicy.resolveAndroidSharedLinkerFlags(
            "--target=aarch64-linux-android28"
        )

        assertThat(resolved).isEqualTo("--target=aarch64-linux-android28 -Wl,-rpath,\\\$ORIGIN")
    }

    @Test
    fun `resolveAndroidSharedLinkerFlags adds origin runpath once`() {
        val resolved = CMakeLinkPolicy.resolveAndroidSharedLinkerFlags(
            "--target=aarch64-linux-android28 -Wl,-rpath,\\\$ORIGIN"
        )

        assertThat(resolved).isEqualTo("--target=aarch64-linux-android28 -Wl,-rpath,\\\$ORIGIN")
    }

    @Test
    fun `resolveAndroidSharedLinkerFlags escapes legacy unescaped origin runpath`() {
        val resolved = CMakeLinkPolicy.resolveAndroidSharedLinkerFlags(
            "--target=aarch64-linux-android28 -Wl,-rpath,\$ORIGIN"
        )

        assertThat(resolved).isEqualTo("--target=aarch64-linux-android28 -Wl,-rpath,\\\$ORIGIN")
    }
}
