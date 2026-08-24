package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import androidx.annotation.StringRes
import com.wuxianggujun.tinaide.core.i18n.Strings

internal data class CompilerSettingsOptionSpec(
    val value: String,
    @param:StringRes @get:StringRes val labelRes: Int
)

internal object CompilerSettingsSectionSupport {

    fun buildRuntimeModeOptions(linuxEnvironmentEnabled: Boolean): List<CompilerSettingsOptionSpec> = buildList {
        add(
            CompilerSettingsOptionSpec(
                value = "native",
                labelRes = Strings.settings_cmake_run_mode_native
            )
        )
        if (linuxEnvironmentEnabled) {
            add(
                CompilerSettingsOptionSpec(
                    value = "proot",
                    labelRes = Strings.settings_cmake_run_mode_proot
                )
            )
        }
    }

    fun buildOptimizationOptions(): List<CompilerSettingsOptionSpec> = listOf(
        CompilerSettingsOptionSpec("O0", Strings.opt_dialog_o0),
        CompilerSettingsOptionSpec("O1", Strings.opt_level_o1),
        CompilerSettingsOptionSpec("O2", Strings.opt_dialog_o2),
        CompilerSettingsOptionSpec("O3", Strings.opt_level_o3)
    )

    fun buildCmakeGeneratorOptions(): List<CompilerSettingsOptionSpec> = listOf(
        CompilerSettingsOptionSpec("Unix Makefiles", Strings.generator_make),
        CompilerSettingsOptionSpec("Ninja", Strings.generator_ninja)
    )

    @StringRes
    fun resolveRuntimeModeLabel(runMode: String): Int = if (runMode == "proot") {
        Strings.settings_cmake_run_mode_proot
    } else {
        Strings.settings_cmake_run_mode_native
    }

    @StringRes
    fun resolveOptimizationDisplayLabel(optimizationLevel: String): Int? = when (optimizationLevel) {
        "O0" -> Strings.opt_level_o0
        "O1" -> Strings.opt_level_o1
        "O2" -> Strings.opt_level_o2
        "O3" -> Strings.opt_level_o3
        else -> null
    }

    @StringRes
    fun resolveCmakeGeneratorDisplayLabel(generator: String): Int? = when (generator) {
        "Unix Makefiles" -> Strings.generator_make
        "Ninja" -> Strings.generator_ninja
        else -> null
    }

    fun resolveArchiveFileType(fileName: String): String = when {
        fileName.endsWith(".tar.gz", ignoreCase = true) -> "tar.gz"
        fileName.endsWith(".tar.xz", ignoreCase = true) -> "tar.xz"
        fileName.endsWith(".tar", ignoreCase = true) -> "tar"
        else -> "tar.gz"
    }

    fun resolveArchiveExtension(fileName: String): String = ".${resolveArchiveFileType(fileName)}"

    fun resolveArchiveBaseName(fileName: String): String {
        val trimmed = fileName.trim()
        return when {
            trimmed.endsWith(".tar.gz", ignoreCase = true) -> trimmed.dropLast(".tar.gz".length)
            trimmed.endsWith(".tar.xz", ignoreCase = true) -> trimmed.dropLast(".tar.xz".length)
            trimmed.endsWith(".tar", ignoreCase = true) -> trimmed.dropLast(".tar".length)
            else -> trimmed.substringBeforeLast('.', trimmed)
        }.ifBlank { trimmed.ifBlank { "sysroot" } }
    }
}
