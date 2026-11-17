package com.wuxianggujun.tinaide.core.packages

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.project.VcpkgProjectSettings
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], manifest = Config.NONE)
class VcpkgAndroidCatalogTest {
    private val available = listOf(
        port("fmt", ""), port("windows-only", "windows"), port("arm-library", "android & arm64"),
        port("x64-library", "android & x64"), port("unknown", "!futureos"), port("dynamic", "!static"),
    )

    @Test fun catalogAndCountUseOnlyCompatibleUpstreamEntries() {
        val catalog = VcpkgAndroidCatalog(available, "arm64-v8a")
        assertThat(catalog.ports.map { it.name }).containsExactly("arm-library", "fmt").inOrder()
        assertThat(catalog.ports.size).isEqualTo(2)
        assertThat(VcpkgAndroidCatalog(available, "x86_64").ports.map { it.name })
            .containsExactly("fmt", "x64-library").inOrder()
        assertThat(catalog.support("windows-only")).isEqualTo(VcpkgPlatformSupport.UNSUPPORTED)
        assertThat(catalog.support("unknown")).isEqualTo(VcpkgPlatformSupport.UNKNOWN)
        assertThat(catalog.support("missing")).isEqualTo(VcpkgPlatformSupport.UNKNOWN)
    }

    @Test fun entriesHiddenByTheAndroidCatalogStayAvailableForRemoval() {
        val project = VcpkgProjectCatalog(VcpkgProjectSettings(VcpkgProjectSettings.MANIFEST),
            available + port("deleted-upstream", ""), setOf("windows-only", "deleted-upstream"), mapOf("fmt" to "12"))
        val catalog = VcpkgAndroidCatalog(available, "arm64-v8a")
        assertThat(catalog.orphanDependencies(project, "").map { it.name })
            .containsExactly("deleted-upstream", "windows-only").inOrder()
        assertThat(catalog.orphanDependencies(project, "windows").map { it.name }).containsExactly("windows-only")
        assertThat(catalog.orphanDependencies(project, "fmt")).isEmpty()
        assertThat(catalog.orphanDependencies(null, "")).isEmpty()
        assertThat(catalog.ports.map { it.name }).containsExactly("arm-library", "fmt")
        assertThat(catalog.support("deleted-upstream")).isEqualTo(VcpkgPlatformSupport.UNKNOWN)
    }

    @Test fun updatedCatalogReevaluatesSupportInsteadOfReusingPreviousMatches() {
        val old = VcpkgAndroidCatalog(listOf(port("changed", "")), "arm64-v8a")
        val updated = VcpkgAndroidCatalog(listOf(port("changed", "!android")), "arm64-v8a")
        assertThat(old.ports).hasSize(1)
        assertThat(updated.ports).isEmpty()
        assertThat(updated.support("changed")).isEqualTo(VcpkgPlatformSupport.UNSUPPORTED)
    }

    @Test fun searchExplainsEmptyResultsCausedByCompatibility() {
        val catalog = VcpkgAndroidCatalog(available, "arm64-v8a")
        assertThat(catalog.search("fmt").ports.map { it.name }).containsExactly("fmt")
        assertThat(catalog.search("  FMT ").ports.map { it.name }).containsExactly("fmt")
        assertThat(catalog.search("windows-only").ports).isEmpty()
        assertThat(catalog.search("windows-only").incompatible.map { it.name }).containsExactly("windows-only")
        assertThat(catalog.search("missing").ports).isEmpty()
        assertThat(catalog.search("missing").incompatible).isEmpty()
        assertThat(catalog.search("").ports.map { it.name }).containsExactly("arm-library", "fmt").inOrder()
        assertThat(catalog.totalCount).isEqualTo(6)
    }

    @Test fun verifiedPortsStayExplicit() {
        assertThat(VcpkgVerifiedPorts.isVerified("fmt")).isTrue()
        assertThat(VcpkgVerifiedPorts.isVerified("zlib")).isFalse()
    }

    @Test fun bundledCatalogIsFullyUnderstoodForBothArchitectures() = runTest {
        val ports = VcpkgCatalogStore(RuntimeEnvironment.getApplication()).readLocal().ports
        assertThat(ports.size).isGreaterThan(2000)
        for (abi in listOf("arm64-v8a", "x86_64")) {
            val catalog = VcpkgAndroidCatalog(ports, abi)
            assertThat(ports.filter { catalog.support(it.name) == VcpkgPlatformSupport.UNKNOWN }.map { it.name }).isEmpty()
            assertThat(catalog.ports.map { it.name }).containsAtLeast("fmt", "zlib", "nlohmann-json")
            assertThat(catalog.ports.map { it.name }).containsNoneOf("directx-headers", "libsystemd")
            assertThat(catalog.ports.size).isLessThan(ports.size)
            println("Android vcpkg catalog: $abi ${catalog.ports.size}/${ports.size}")
        }
    }

    private fun port(name: String, supports: String) = VcpkgPort(name, "1", "", supports)
}
