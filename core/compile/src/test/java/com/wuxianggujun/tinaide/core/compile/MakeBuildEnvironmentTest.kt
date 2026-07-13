package com.wuxianggujun.tinaide.core.compile

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.ndk.AndroidSysrootManager
import com.wuxianggujun.tinaide.core.packages.InstalledPackagePathResolver
import java.io.File
import org.junit.Test

class MakeBuildEnvironmentTest {

    @Test
    fun `build exports package paths and explicit project flags via environment`() {
        val packagePaths = InstalledPackagePathResolver.PackagePaths(
            includeDirs = listOf(File("/pkg/include")),
            libDirs = listOf(File("/pkg/lib")),
            prefixDirs = emptyList(),
            pkgConfigDirs = listOf(File("/pkg/lib/pkgconfig")),
            linkLibraries = listOf("SDL3"),
            runtimeLibDirs = listOf(File("/pkg/runtime"))
        )

        val env = MakeBuildEnvironment.build(
            packagePaths = packagePaths,
            nativeCFlags = " -DDEBUG ",
            nativeCppFlags = " -Wall\n-Wextra ",
            nativeLdFlags = " -Wl,--as-needed ",
            nativeLdLibs = " -lSDL3 ",
            extraLibraryDirs = listOf("/sysroot/lib"),
            pathMapper = { "/guest${it.replace('\\', '/')}" }
        )

        assertThat(env["CPATH"]).contains("pkg/include")
        assertThat(env["LIBRARY_PATH"]).contains("sysroot/lib")
        assertThat(env["LIBRARY_PATH"]).contains("pkg/lib")
        assertThat(env["PKG_CONFIG_PATH"]).contains("pkg/lib/pkgconfig")
        assertThat(env["LD_LIBRARY_PATH"]).contains("pkg/runtime")
        assertThat(env).doesNotContainKey("CPPFLAGS")
        assertThat(env["CFLAGS"]).isEqualTo("-DDEBUG")
        assertThat(env["CXXFLAGS"]).isEqualTo("-Wall -Wextra")
        assertThat(env["LDFLAGS"]).isEqualTo("-Wl,--as-needed")
        assertThat(env["LDLIBS"]).isEqualTo("-lSDL3")
    }

    @Test
    fun `build can export linker compatibility flags before project ld flags`() {
        val packagePaths = InstalledPackagePathResolver.PackagePaths(
            includeDirs = emptyList(),
            libDirs = emptyList(),
            prefixDirs = emptyList(),
            pkgConfigDirs = emptyList(),
            linkLibraries = emptyList(),
            runtimeLibDirs = emptyList()
        )
        val ldFlags = AndroidLinkerCompatibilityFlags.mergeWithUserLdFlags(
            arch = AndroidSysrootManager.Companion.Arch.ARM64,
            apiLevel = 28,
            userLdFlags = " -Wl,--as-needed ",
        )

        val env = MakeBuildEnvironment.build(
            packagePaths = packagePaths,
            nativeLdFlags = ldFlags,
        )

        assertThat(env["LDFLAGS"]).isEqualTo(
            "${AndroidLinkerCompatibilityFlags.DISABLE_RELATIVE_RELOCATION_PACKING} -Wl,--as-needed"
        )
    }

    @Test
    fun `build keeps package libraries discoverable but does not auto export ldlibs`() {
        val packagePaths = InstalledPackagePathResolver.PackagePaths(
            includeDirs = listOf(File("/pkg/include")),
            libDirs = listOf(File("/pkg/lib")),
            prefixDirs = emptyList(),
            pkgConfigDirs = emptyList(),
            linkLibraries = listOf("SDL3"),
            runtimeLibDirs = emptyList()
        )

        val env = MakeBuildEnvironment.build(
            packagePaths = packagePaths,
            nativeLdLibs = "",
            pathMapper = { it.replace('\\', '/') }
        )

        assertThat(env["CPATH"]).contains("pkg/include")
        assertThat(env["LIBRARY_PATH"]).contains("pkg/lib")
        assertThat(env).doesNotContainKey("CPPFLAGS")
        assertThat(env).doesNotContainKey("LDLIBS")
    }

    @Test
    fun `build omits empty keys`() {
        val env = MakeBuildEnvironment.build(
            packagePaths = InstalledPackagePathResolver.PackagePaths(
                includeDirs = emptyList(),
                libDirs = emptyList(),
                prefixDirs = emptyList(),
                pkgConfigDirs = emptyList(),
                linkLibraries = emptyList(),
                runtimeLibDirs = emptyList()
            )
        )

        assertThat(env).isEmpty()
    }
}
