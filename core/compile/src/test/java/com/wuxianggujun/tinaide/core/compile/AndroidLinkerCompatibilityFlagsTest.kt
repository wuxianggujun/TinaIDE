package com.wuxianggujun.tinaide.core.compile

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.ndk.AndroidSysrootManager
import org.junit.Test

class AndroidLinkerCompatibilityFlagsTest {

    @Test
    fun `forTarget disables relative relocation packing for arm64 api 28 and newer`() {
        assertThat(
            AndroidLinkerCompatibilityFlags.forTarget(
                arch = AndroidSysrootManager.Companion.Arch.ARM64,
                apiLevel = 28,
            )
        ).containsExactly(AndroidLinkerCompatibilityFlags.DISABLE_RELATIVE_RELOCATION_PACKING)

        assertThat(
            AndroidLinkerCompatibilityFlags.forTarget(
                arch = AndroidSysrootManager.Companion.Arch.ARM64,
                apiLevel = 35,
            )
        ).containsExactly(AndroidLinkerCompatibilityFlags.DISABLE_RELATIVE_RELOCATION_PACKING)
    }

    @Test
    fun `forTarget skips non relr targets`() {
        assertThat(
            AndroidLinkerCompatibilityFlags.forTarget(
                arch = AndroidSysrootManager.Companion.Arch.ARM64,
                apiLevel = 27,
            )
        ).isEmpty()

        assertThat(
            AndroidLinkerCompatibilityFlags.forTarget(
                arch = AndroidSysrootManager.Companion.Arch.X86_64,
                apiLevel = 28,
            )
        ).isEmpty()
    }

    @Test
    fun `mergeWithUserLdFlags keeps compatibility flag before user overrides`() {
        val merged = AndroidLinkerCompatibilityFlags.mergeWithUserLdFlags(
            arch = AndroidSysrootManager.Companion.Arch.ARM64,
            apiLevel = 28,
            userLdFlags = " -Wl,-z,pack-relative-relocs ",
        )

        assertThat(merged).isEqualTo(
            "${AndroidLinkerCompatibilityFlags.DISABLE_RELATIVE_RELOCATION_PACKING} " +
                "-Wl,-z,pack-relative-relocs"
        )
    }
}
