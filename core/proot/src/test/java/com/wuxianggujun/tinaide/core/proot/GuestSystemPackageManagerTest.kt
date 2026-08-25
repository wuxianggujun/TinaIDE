package com.wuxianggujun.tinaide.core.proot

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GuestSystemPackageManagerTest {

    @Test
    fun isSafePackageArgument_acceptsNamesArchitecturesAndVersions() {
        assertThat(GuestSystemPackageManager.isSafePackageArgument("python3")).isTrue()
        assertThat(GuestSystemPackageManager.isSafePackageArgument("libc6:arm64")).isTrue()
        assertThat(GuestSystemPackageManager.isSafePackageArgument("python3=3.12.1-r0")).isTrue()
    }

    @Test
    fun isSafePackageArgument_rejectsOptionsPathsAndWhitespace() {
        assertThat(GuestSystemPackageManager.isSafePackageArgument("--root=/tmp/rootfs")).isFalse()
        assertThat(GuestSystemPackageManager.isSafePackageArgument("../package")).isFalse()
        assertThat(GuestSystemPackageManager.isSafePackageArgument("package other")).isFalse()
    }
}
