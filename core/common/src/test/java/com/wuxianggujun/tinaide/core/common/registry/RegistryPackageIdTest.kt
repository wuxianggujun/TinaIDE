package com.wuxianggujun.tinaide.core.common.registry

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RegistryPackageIdTest {
    @Test
    fun `accepts registry package ids`() {
        assertThat(RegistryPackageId.isValid("sdl3-image")).isTrue()
        assertThat(RegistryPackageId.isValid("box2d_3.runtime")).isTrue()
    }

    @Test
    fun `rejects traversal absolute and blank ids`() {
        listOf("", "../plugins", "/data/local", "C:/temp", ".", "name with spaces").forEach { value ->
            assertThat(RegistryPackageId.isValid(value)).isFalse()
        }
    }
}
