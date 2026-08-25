package com.wuxianggujun.tinaide.core.compile

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.compile.action.LaunchIntent
import org.junit.Test

class EffectiveBuildConfigResolverTest {

    @Test
    fun `make build keeps run config debug for normal run`() {
        val resolved = EffectiveBuildConfigResolver.resolveBuildTypeAndDebugInfo(
            launch = LaunchIntent.Run(OutputMode.TERMINAL),
            buildSystem = BuildSystem.MAKE,
            cmakeBuildType = CMakeBuildTypeOption.RELEASE,
            configuredBuildType = BuildType.DEBUG
        )

        assertThat(resolved.first).isEqualTo(BuildType.DEBUG)
        assertThat(resolved.second).isTrue()
    }

    @Test
    fun `single file build keeps run config release for build mode`() {
        val resolved = EffectiveBuildConfigResolver.resolveBuildTypeAndDebugInfo(
            launch = LaunchIntent.None,
            buildSystem = BuildSystem.SINGLE_FILE,
            cmakeBuildType = CMakeBuildTypeOption.DEBUG,
            configuredBuildType = BuildType.RELEASE
        )

        assertThat(resolved.first).isEqualTo(BuildType.RELEASE)
        assertThat(resolved.second).isFalse()
    }

    @Test
    fun `cmake maps effective cmake build type to general build options`() {
        val resolved = EffectiveBuildConfigResolver.resolveBuildTypeAndDebugInfo(
            launch = LaunchIntent.Run(OutputMode.TERMINAL),
            buildSystem = BuildSystem.CMAKE,
            cmakeBuildType = CMakeBuildTypeOption.RELEASE,
            configuredBuildType = BuildType.DEBUG
        )

        assertThat(resolved.first).isEqualTo(BuildType.RELEASE)
        assertThat(resolved.second).isFalse()
    }

    @Test
    fun `cmake normal run uses run configuration build type`() {
        val resolved = EffectiveBuildConfigResolver.resolveCMakeBuildType(
            launch = LaunchIntent.Run(OutputMode.TERMINAL),
            buildSystem = BuildSystem.CMAKE,
            configuredBuildType = CMakeBuildTypeOption.RELEASE,
        )

        assertThat(resolved).isEqualTo(CMakeBuildTypeOption.RELEASE)
    }

    @Test
    fun `non cmake build ignores cmake variant`() {
        val resolved = EffectiveBuildConfigResolver.resolveCMakeBuildType(
            launch = LaunchIntent.Run(OutputMode.TERMINAL),
            buildSystem = BuildSystem.MAKE,
            configuredBuildType = CMakeBuildTypeOption.REL_WITH_DEB_INFO,
        )

        assertThat(resolved).isEqualTo(CMakeBuildTypeOption.DEBUG)
    }

    @Test
    fun `debug launch forces actual cmake debug variant`() {
        val resolved = EffectiveBuildConfigResolver.resolveCMakeBuildType(
            launch = LaunchIntent.Debug,
            buildSystem = BuildSystem.CMAKE,
            configuredBuildType = CMakeBuildTypeOption.RELEASE,
        )

        assertThat(resolved).isEqualTo(CMakeBuildTypeOption.DEBUG)
    }

    @Test
    fun `debug launch intent always forces debug`() {
        val resolved = EffectiveBuildConfigResolver.resolveBuildTypeAndDebugInfo(
            launch = LaunchIntent.Debug,
            buildSystem = BuildSystem.MAKE,
            cmakeBuildType = CMakeBuildTypeOption.RELEASE,
            configuredBuildType = BuildType.RELEASE
        )

        assertThat(resolved.first).isEqualTo(BuildType.DEBUG)
        assertThat(resolved.second).isTrue()
    }
}
