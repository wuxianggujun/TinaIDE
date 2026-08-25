package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.i18n.Strings
import org.junit.Test

class CompilerSettingsSectionSupportTest {

    @Test
    fun buildRuntimeModeOptions_shouldOnlyExposeNativeWhenLinuxEnvironmentDisabled() {
        assertThat(
            CompilerSettingsSectionSupport.buildRuntimeModeOptions(
                linuxEnvironmentEnabled = false
            )
        ).containsExactly(
            CompilerSettingsOptionSpec(
                value = "native",
                labelRes = Strings.settings_cmake_run_mode_native
            )
        )
    }

    @Test
    fun buildRuntimeModeOptions_shouldExposeProotWhenLinuxEnvironmentEnabled() {
        assertThat(
            CompilerSettingsSectionSupport.buildRuntimeModeOptions(
                linuxEnvironmentEnabled = true
            )
        ).containsExactly(
            CompilerSettingsOptionSpec(
                value = "native",
                labelRes = Strings.settings_cmake_run_mode_native
            ),
            CompilerSettingsOptionSpec(
                value = "proot",
                labelRes = Strings.settings_cmake_run_mode_proot
            )
        ).inOrder()
    }

    @Test
    fun resolveRuntimeModeLabel_shouldFallbackToNativeForUnknownMode() {
        assertThat(
            CompilerSettingsSectionSupport.resolveRuntimeModeLabel("proot")
        ).isEqualTo(Strings.settings_cmake_run_mode_proot)
        assertThat(
            CompilerSettingsSectionSupport.resolveRuntimeModeLabel("unexpected")
        ).isEqualTo(Strings.settings_cmake_run_mode_native)
    }

    @Test
    fun resolveDisplayLabels_shouldMapKnownCompilerAndCmakeValues() {
        assertThat(
            CompilerSettingsSectionSupport.resolveOptimizationDisplayLabel("O2")
        ).isEqualTo(Strings.opt_level_o2)
        assertThat(
            CompilerSettingsSectionSupport.resolveOptimizationDisplayLabel("Oz")
        ).isNull()

        assertThat(
            CompilerSettingsSectionSupport.resolveCmakeGeneratorDisplayLabel("Unix Makefiles")
        ).isEqualTo(Strings.generator_make)
        assertThat(
            CompilerSettingsSectionSupport.resolveCmakeGeneratorDisplayLabel("Meson")
        ).isNull()
    }

    @Test
    fun buildDialogOptions_shouldExposeStableOrderForSelections() {
        assertThat(
            CompilerSettingsSectionSupport.buildOptimizationOptions()
        ).containsExactly(
            CompilerSettingsOptionSpec("O0", Strings.opt_dialog_o0),
            CompilerSettingsOptionSpec("O1", Strings.opt_level_o1),
            CompilerSettingsOptionSpec("O2", Strings.opt_dialog_o2),
            CompilerSettingsOptionSpec("O3", Strings.opt_level_o3)
        ).inOrder()

        assertThat(
            CompilerSettingsSectionSupport.buildCmakeGeneratorOptions()
        ).containsExactly(
            CompilerSettingsOptionSpec("Unix Makefiles", Strings.generator_make),
            CompilerSettingsOptionSpec("Ninja", Strings.generator_ninja)
        ).inOrder()
    }

    @Test
    fun resolveArchiveFileTypeAndExtension_shouldDetectSupportedTarFormats() {
        assertThat(
            CompilerSettingsSectionSupport.resolveArchiveFileType("llvm-aarch64.tar.gz")
        ).isEqualTo("tar.gz")
        assertThat(
            CompilerSettingsSectionSupport.resolveArchiveFileType("llvm-aarch64.tar.xz")
        ).isEqualTo("tar.xz")
        assertThat(
            CompilerSettingsSectionSupport.resolveArchiveFileType("llvm-aarch64.tar")
        ).isEqualTo("tar")
        assertThat(
            CompilerSettingsSectionSupport.resolveArchiveFileType("llvm-aarch64.zip")
        ).isEqualTo("tar.gz")

        assertThat(
            CompilerSettingsSectionSupport.resolveArchiveExtension("llvm-aarch64.tar.xz")
        ).isEqualTo(".tar.xz")
        assertThat(
            CompilerSettingsSectionSupport.resolveArchiveExtension("llvm-aarch64.zip")
        ).isEqualTo(".tar.gz")
    }
}
