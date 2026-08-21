package com.wuxianggujun.tinaide.plugin.lsp

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LspToolchainPackagePolicyTest {

    @Test
    fun areValid_acceptsSupportedPackageSpecifications() {
        assertThat(LspToolchainPackagePolicy.areValid("system", listOf("python3", "python3=3.12.1-r0")))
            .isTrue()
        assertThat(LspToolchainPackagePolicy.areValid("pip", listOf("python-lsp-server[all]>=1.10,<2")))
            .isTrue()
        assertThat(LspToolchainPackagePolicy.areValid("npm", listOf("@scope/language-server@^2.0.0")))
            .isTrue()
    }

    @Test
    fun areValid_rejectsPackageManagerOptionsAndUrls() {
        assertThat(LspToolchainPackagePolicy.areValid("system", listOf("--root=/tmp/root"))).isFalse()
        assertThat(LspToolchainPackagePolicy.areValid("pip", listOf("--index-url"))).isFalse()
        assertThat(LspToolchainPackagePolicy.areValid("pip", listOf("https://example.com/package.whl"))).isFalse()
        assertThat(LspToolchainPackagePolicy.areValid("npm", listOf("--prefix"))).isFalse()
    }

    @Test
    fun areFallbackVersionsValid_rejectsOptionLikeVersions() {
        assertThat(LspToolchainPackagePolicy.areFallbackVersionsValid(listOf("3.12.1-r0"))).isTrue()
        assertThat(LspToolchainPackagePolicy.areFallbackVersionsValid(listOf("--root"))).isFalse()
    }
}
