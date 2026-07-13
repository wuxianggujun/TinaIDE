package com.wuxianggujun.tinaide.core.packages

import android.os.Build
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.packages.model.InstallType
import com.wuxianggujun.tinaide.core.packages.model.Platform
import com.wuxianggujun.tinaide.core.packages.store.LocalInstallStateStore
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class InstalledPackagePathResolverTest {

    private val context = RuntimeEnvironment.getApplication().applicationContext
    private val installRoot = File(context.filesDir, "installed-packages")
    private val installStateStore = LocalInstallStateStore(context)

    @Before
    fun setUp() {
        installRoot.deleteRecursively()
        installRoot.mkdirs()
        installStateStore.clear()
    }

    @After
    fun tearDown() {
        installRoot.deleteRecursively()
        installStateStore.clear()
    }

    @Test
    fun `resolve exposes installed header and source package include and pkgconfig paths`() {
        createHeaderPackage("nlohmann-json", "nlohmann/json.hpp")
        createSourcePackage("tinyxml2", "tinyxml2.h", "tinyxml2.cpp")
        createHeaderPackage("not-installed", "ignored.hpp")

        installStateStore.setInstalled(
            packageId = "nlohmann-json",
            platform = Platform.ANDROID,
            version = "3.12.0",
            packageName = "JSON for Modern C++",
            installType = InstallType.DOWNLOAD
        )
        installStateStore.setInstalled(
            packageId = "tinyxml2",
            platform = Platform.ANDROID,
            version = "11.0.0",
            packageName = "TinyXML-2",
            installType = InstallType.DOWNLOAD
        )

        val paths = InstalledPackagePathResolver.resolve(context)

        assertThat(paths.includeDirs.map { it.relativeTo(installRoot).invariantSeparatorsPath })
            .containsExactly(
                "nlohmann-json/include",
                "tinyxml2/include"
            )
            .inOrder()
        assertThat(paths.pkgConfigDirs.map { it.relativeTo(installRoot).invariantSeparatorsPath })
            .containsExactly(
                "nlohmann-json/pkgconfig",
                "tinyxml2/pkgconfig"
            )
            .inOrder()
        assertThat(paths.prefixDirs.map { it.relativeTo(installRoot).invariantSeparatorsPath })
            .containsExactly("nlohmann-json", "tinyxml2")
            .inOrder()
        assertThat(paths.linkLibraries).isEmpty()
        assertThat(paths.includeDirs.map { it.absolutePath }).doesNotContain(
            File(installRoot, "not-installed/include").absolutePath
        )
    }

    @Test
    fun `resolve discovers libraries only for installed Android packages`() {
        val deviceAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        createBinaryPackage(
            packageId = "sdl3",
            abi = deviceAbi,
            sharedLibrary = "libSDL3.so",
            staticLibrary = "libSDL3.a"
        )
        createBinaryPackage(
            packageId = "not-installed-lib",
            abi = deviceAbi,
            sharedLibrary = "libignored.so",
            staticLibrary = "libignored.a"
        )

        installStateStore.setInstalled(
            packageId = "sdl3",
            platform = Platform.ANDROID,
            version = "3.5.0",
            packageName = "SDL3",
            installType = InstallType.DOWNLOAD
        )

        val paths = InstalledPackagePathResolver.resolve(context)

        assertThat(paths.libDirs.map { it.relativeTo(installRoot).invariantSeparatorsPath })
            .containsAtLeast(
                "sdl3/lib",
                "sdl3/lib/$deviceAbi"
            )
        assertThat(paths.pkgConfigDirs.map { it.relativeTo(installRoot).invariantSeparatorsPath })
            .containsExactly(
                "sdl3/pkgconfig",
                "sdl3/lib/pkgconfig",
                "sdl3/lib/$deviceAbi/pkgconfig"
            )
        assertThat(paths.linkLibraries).containsExactly("SDL3")
        assertThat(paths.runtimeLibDirs.map { it.relativeTo(installRoot).invariantSeparatorsPath })
            .containsExactly("sdl3/lib/$deviceAbi")
        assertThat(paths.linkLibraries).doesNotContain("ignored")
    }

    @Test
    fun `resolve ignores stale package directories without installed state`() {
        val deviceAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        createBinaryPackage(
            packageId = "stale-sdl3",
            abi = deviceAbi,
            sharedLibrary = "libSDL3.so",
            staticLibrary = "libSDL3.a"
        )

        val paths = InstalledPackagePathResolver.resolve(context)

        assertThat(paths.runtimeLibDirs).isEmpty()
        assertThat(paths.linkLibraries).isEmpty()
        assertThat(paths.libDirs).isEmpty()
    }

    private fun createHeaderPackage(packageId: String, headerPath: String) {
        val packageRoot = File(installRoot, packageId)
        writeFile(File(packageRoot, "include/$headerPath"), "")
        writeFile(File(packageRoot, "pkgconfig/$packageId.pc"), "Name: $packageId\n")
    }

    private fun createSourcePackage(packageId: String, headerName: String, sourceName: String) {
        val packageRoot = File(installRoot, packageId)
        writeFile(File(packageRoot, "include/$headerName"), "")
        writeFile(File(packageRoot, "src/$sourceName"), "")
        writeFile(File(packageRoot, "pkgconfig/$packageId.pc"), "Name: $packageId\n")
    }

    private fun createBinaryPackage(
        packageId: String,
        abi: String,
        sharedLibrary: String,
        staticLibrary: String
    ) {
        val packageRoot = File(installRoot, packageId)
        writeFile(File(packageRoot, "include/$packageId.h"), "")
        writeFile(File(packageRoot, "lib/$abi/$sharedLibrary"), "")
        writeFile(File(packageRoot, "lib/$staticLibrary"), "")
        writeFile(File(packageRoot, "pkgconfig/$packageId.pc"), "Name: $packageId\n")
        writeFile(File(packageRoot, "lib/pkgconfig/$packageId-root.pc"), "Name: $packageId\n")
        writeFile(File(packageRoot, "lib/$abi/pkgconfig/$packageId-abi.pc"), "Name: $packageId\n")
    }

    private fun writeFile(file: File, content: String) {
        file.parentFile?.mkdirs()
        file.writeText(content)
    }
}
