package com.wuxianggujun.tinaide.core.compile

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AndroidCppRuntimeLinkageTest {

    @Test
    fun `C++ executable statically links its runtime`() {
        val flags = AndroidCppRuntimeLinkage.flagsForOutput(
            isCpp = true,
            outputIsSharedLibrary = false,
        )

        assertThat(flags).containsExactly(AndroidCppRuntimeLinkage.STATIC_EXECUTABLE_FLAG)
    }

    @Test
    fun `shared library keeps shared runtime policy`() {
        val flags = AndroidCppRuntimeLinkage.flagsForOutput(
            isCpp = true,
            outputIsSharedLibrary = true,
        )

        assertThat(flags).isEmpty()
    }

    @Test
    fun `C executable does not link C++ runtime`() {
        val flags = AndroidCppRuntimeLinkage.flagsForOutput(
            isCpp = false,
            outputIsSharedLibrary = false,
        )

        assertThat(flags).isEmpty()
    }

    @Test
    fun `appendToLinkerFlags does not duplicate static runtime flag`() {
        val flags = AndroidCppRuntimeLinkage.appendToLinkerFlags(
            linkerFlags = "--target=aarch64-linux-android28 -static-libstdc++",
            isCpp = true,
            outputIsSharedLibrary = false,
        )

        assertThat(flags).isEqualTo("--target=aarch64-linux-android28 -static-libstdc++")
    }
}
