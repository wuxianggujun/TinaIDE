package com.wuxianggujun.tinaide.core.packages

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PackageVersionComparisonTest {
    @Test
    fun `same upstream version with a newer packaging revision is an update`() {
        assertThat(isNewerPackageVersion("6.0", "6.0", 3, 2)).isTrue()
        assertThat(isNewerPackageVersion("6.0", "6.0", 3, null)).isTrue()
        assertThat(isNewerPackageVersion("6.0", "6.0", 3, 3)).isFalse()
        assertThat(isNewerPackageVersion("6.0", "6.0", 2, 3)).isFalse()
    }

    @Test
    fun `upstream version has priority over packaging revision`() {
        assertThat(isNewerPackageVersion("6.1", "6.0", 1, 3)).isTrue()
        assertThat(isNewerPackageVersion("5.9", "6.0", 9, 3)).isFalse()
        assertThat(isNewerPackageVersion("6.0.0", "6.0")).isFalse()
    }
}
