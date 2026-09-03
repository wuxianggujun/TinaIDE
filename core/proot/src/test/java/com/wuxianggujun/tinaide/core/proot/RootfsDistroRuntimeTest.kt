package com.wuxianggujun.tinaide.core.proot

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RootfsDistroRuntimeTest {

    @Test
    fun onlyBuiltInDistro_shouldExposeUbuntuOnly() {
        val distros = listOf(
            distroOption("alpine", "Alpine Linux"),
            distroOption("debian", "Debian"),
            distroOption("ubuntu", "Ubuntu"),
        )

        val filtered = distros.onlyBuiltInDistro(
            SelfHostedLinuxDistroRuntime.DEFAULT_DISTRO_ID,
        )

        assertThat(filtered.map { distro -> distro.id }).containsExactly("ubuntu")
    }

    @Test
    fun onlyBuiltInDistro_shouldReturnEmptyWhenUbuntuIsUnavailable() {
        val distros = listOf(
            distroOption("alpine", "Alpine Linux"),
            distroOption("debian", "Debian"),
        )

        val filtered = distros.onlyBuiltInDistro("ubuntu")

        assertThat(filtered).isEmpty()
    }

    private fun distroOption(
        id: String,
        displayName: String,
    ): RootfsDistroRuntime.DistroOption = RootfsDistroRuntime.DistroOption(
        id = id,
        displayName = displayName,
        description = "official rootfs",
        packageManager = RootfsPackageManager.UNKNOWN,
    )
}
