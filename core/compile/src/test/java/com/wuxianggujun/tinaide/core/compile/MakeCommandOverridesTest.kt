package com.wuxianggujun.tinaide.core.compile

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MakeCommandOverridesTest {

    @Test
    fun `isValidSysrootApiLevel accepts configured range`() {
        assertThat(
            MakeCommandOverrides.isValidSysrootApiLevel(MakeCommandOverrides.MIN_SYSROOT_API_LEVEL)
        ).isTrue()
        assertThat(
            MakeCommandOverrides.isValidSysrootApiLevel(MakeCommandOverrides.MAX_SYSROOT_API_LEVEL)
        ).isTrue()
    }

    @Test
    fun `isValidSysrootApiLevel rejects out of range values`() {
        assertThat(
            MakeCommandOverrides.isValidSysrootApiLevel(MakeCommandOverrides.MIN_SYSROOT_API_LEVEL - 1)
        ).isFalse()
        assertThat(
            MakeCommandOverrides.isValidSysrootApiLevel(MakeCommandOverrides.MAX_SYSROOT_API_LEVEL + 1)
        ).isFalse()
    }

    @Test
    fun `buildVariableAssignment contains sysroot and cxx include for make CXX`() {
        val assignment = MakeCommandOverrides.buildVariableAssignment(
            variable = "CXX",
            shellCommand = "/data/user/0/pkg/files/toolchains/builtin/bin/clang++",
            extraArgs = listOf(
                "--target=aarch64-linux-android28",
                "--sysroot=/data/user/0/pkg/files/android-sysroot",
                "-isystem",
                "/data/user/0/pkg/files/android-sysroot/usr/include/c++/v1"
            )
        )

        assertThat(assignment).contains("CXX=/data/user/0/pkg/files/toolchains/builtin/bin/clang++")
        assertThat(assignment).contains("--sysroot=/data/user/0/pkg/files/android-sysroot")
        assertThat(assignment).contains("/usr/include/c++/v1")
    }

    @Test
    fun `splitCompileAndLinkFlags strips -L from compile flags`() {
        val split = MakeCommandOverrides.splitCompileAndLinkFlags(
            listOf(
                "--target=aarch64-linux-android28",
                "--sysroot=/data/user/0/pkg/files/android-sysroot",
                "-isystem",
                "/data/user/0/pkg/files/android-sysroot/usr/include/c++/v1",
                "-L/data/user/0/pkg/files/android-sysroot/usr/lib/aarch64-linux-android/28"
            )
        )

        assertThat(split.compileFlags).contains("--target=aarch64-linux-android28")
        assertThat(split.compileFlags).contains("--sysroot=/data/user/0/pkg/files/android-sysroot")
        assertThat(split.compileFlags).contains("/data/user/0/pkg/files/android-sysroot/usr/include/c++/v1")
        assertThat(split.compileFlags).doesNotContain("-L/data/user/0/pkg/files/android-sysroot/usr/lib/aarch64-linux-android/28")
        assertThat(split.linkFlags).containsExactly("-L/data/user/0/pkg/files/android-sysroot/usr/lib/aarch64-linux-android/28")
    }
}
