package com.wuxianggujun.tinaide.core.compile

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.linux.LinuxRunModePolicy
import org.junit.Test

class CMakeConfigurationIdentityTest {

    @Test
    fun `from includes settings that require CMake reconfigure`() {
        val identity = CMakeConfigurationIdentity.from(
            BuildOptions(
                compilerType = CompilerType.CLANG,
                toolchainId = " toolchain-a ",
                sysrootProfileId = " sysroot-a ",
                sysrootApiLevel = 29,
                nativeCFlags = " -DFOO=1 ",
                nativeCppFlags = " -DBAR=1 ",
                nativeLdFlags = " -Wl,--no-undefined ",
                nativeLdLibs = " -lSDL3 ",
                nativeCMakeArgs = listOf(" -DUSE_A=ON ", "", "-DUSE_A=ON", "-DUSE_B=OFF"),
                cppStandard = " c++20 ",
                resolvedRunMode = LinuxRunModePolicy.RunMode.NATIVE,
            )
        )

        assertThat(identity.asCMakeCacheEntries()).containsAtLeast(
            CMakeConfigurationIdentity.CMAKE_LINK_POLICY_VERSION_KEY,
            "3",
            CMakeConfigurationIdentity.CMAKE_RUN_MODE_KEY,
            "NATIVE",
            CMakeConfigurationIdentity.CMAKE_COMPILER_TYPE_KEY,
            "CLANG",
            CMakeConfigurationIdentity.CMAKE_TOOLCHAIN_ID_KEY,
            "toolchain-a",
            NativeRuntimeIdentity.CMAKE_SYSROOT_PROFILE_ID_KEY,
            "sysroot-a",
            NativeRuntimeIdentity.CMAKE_SYSROOT_API_LEVEL_KEY,
            "29",
            CMakeConfigurationIdentity.CMAKE_CPP_STANDARD_KEY,
            "c++20",
            CMakeConfigurationIdentity.CMAKE_NATIVE_C_FLAGS_KEY,
            "-DFOO=1",
            CMakeConfigurationIdentity.CMAKE_NATIVE_CPP_FLAGS_KEY,
            "-DBAR=1",
            CMakeConfigurationIdentity.CMAKE_NATIVE_LD_FLAGS_KEY,
            "-Wl,--no-undefined",
            CMakeConfigurationIdentity.CMAKE_NATIVE_LD_LIBS_KEY,
            "-lSDL3",
            CMakeConfigurationIdentity.CMAKE_NATIVE_CMAKE_ARGS_KEY,
            "-DUSE_A=ON -DUSE_B=OFF",
        )
    }

    @Test
    fun `from uses proot toolchain sentinel outside native mode`() {
        val identity = CMakeConfigurationIdentity.from(
            BuildOptions(
                toolchainId = "native-toolchain-should-not-leak",
                resolvedRunMode = LinuxRunModePolicy.RunMode.PROOT,
            )
        )

        assertThat(identity.asCMakeCacheEntries()[CMakeConfigurationIdentity.CMAKE_TOOLCHAIN_ID_KEY])
            .isEqualTo("<proot>")
    }
}
