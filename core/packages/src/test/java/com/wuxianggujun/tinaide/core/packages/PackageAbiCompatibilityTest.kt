package com.wuxianggujun.tinaide.core.packages

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PackageAbiCompatibilityTest {

    @Test
    fun isCompatible_shouldAllowAbiIndependentPackages() {
        assertThat(PackageAbiCompatibility.isCompatible(null, arrayOf("x86_64"))).isTrue()
        assertThat(PackageAbiCompatibility.isCompatible(emptyList(), arrayOf("x86_64"))).isTrue()
    }

    @Test
    fun isCompatible_shouldMatchAnySupportedDeviceAbi() {
        assertThat(
            PackageAbiCompatibility.isCompatible(
                requiredAbis = listOf("arm64-v8a", "x86_64"),
                supportedAbis = arrayOf("x86_64", "x86")
            )
        ).isTrue()
    }

    @Test
    fun isCompatible_shouldRejectUnsupportedBinaryPackageAbi() {
        assertThat(
            PackageAbiCompatibility.isCompatible(
                requiredAbis = listOf("arm64-v8a"),
                supportedAbis = arrayOf("x86_64", "x86")
            )
        ).isFalse()
    }

    @Test
    fun currentAppAbi_shouldPreferInstalledNativeLibraryAbiOverDeviceOrder() {
        assertThat(
            PackageAbiCompatibility.currentAppAbi(
                nativeLibraryDir = "/data/app/example/lib/arm64",
                supportedAbis = arrayOf("x86_64", "arm64-v8a")
            )
        ).isEqualTo("arm64-v8a")
    }

    @Test
    fun currentAppAbi_shouldRecognizeX8664VariantAndFallbackWhenPathHasNoAbi() {
        assertThat(
            PackageAbiCompatibility.currentAppAbi(
                nativeLibraryDir = "/data/app/example/lib/x86_64",
                supportedAbis = arrayOf("arm64-v8a", "x86_64")
            )
        ).isEqualTo("x86_64")
        assertThat(
            PackageAbiCompatibility.currentAppAbi(
                nativeLibraryDir = "/data/app/example/lib",
                supportedAbis = arrayOf("arm64-v8a", "x86_64")
            )
        ).isEqualTo("arm64-v8a")
    }
}
