package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.i18n.Strings
import org.junit.Test

class EditorSettingsSectionSupportTest {

    @Test
    fun resolveCustomFontDisplayName_shouldFallbackToDefaultOrUseFileName() {
        assertThat(
            EditorSettingsSectionSupport.resolveCustomFontDisplayName(
                fontPath = "",
                defaultLabel = "Default"
            )
        ).isEqualTo("Default")

        assertThat(
            EditorSettingsSectionSupport.resolveCustomFontDisplayName(
                fontPath = "/fonts/JetBrainsMono.ttf",
                defaultLabel = "Default"
            )
        ).isEqualTo("JetBrainsMono.ttf")
    }

    @Test
    fun resolveEditorThemeDisplayName_shouldPreferConfiguredEntryThenPluginFallback() {
        val entries = listOf("跟随系统", "深色")
        val values = listOf("default", "dark")

        assertThat(
            EditorSettingsSectionSupport.resolveEditorThemeDisplayName(
                currentTheme = "dark",
                themeEntries = entries,
                themeValues = values,
                pluginThemesLabel = "插件主题",
                customThemeLabel = "自定义"
            )
        ).isEqualTo("深色")

        assertThat(
            EditorSettingsSectionSupport.resolveEditorThemeDisplayName(
                currentTheme = "plugin:oceanic",
                themeEntries = entries,
                themeValues = values,
                pluginThemesLabel = "插件主题",
                customThemeLabel = "自定义"
            )
        ).isEqualTo("插件主题")

        assertThat(
            EditorSettingsSectionSupport.resolveEditorThemeDisplayName(
                currentTheme = "CUSTOM",
                themeEntries = entries,
                themeValues = values,
                pluginThemesLabel = "插件主题",
                customThemeLabel = "自定义"
            )
        ).isEqualTo("自定义")
    }

    @Test
    fun buildEditorThemeOptions_shouldZipEntriesAndValuesInOrder() {
        assertThat(
            EditorSettingsSectionSupport.buildEditorThemeOptions(
                themeEntries = listOf("浅色", "深色"),
                themeValues = listOf("light", "dark"),
                customThemeLabel = "自定义"
            )
        ).containsExactly(
            "light" to "浅色",
            "dark" to "深色",
            "CUSTOM" to "自定义"
        ).inOrder()
    }

    @Test
    fun resolveRenderWhitespaceLabelAndOptions_shouldCoverAllModes() {
        assertThat(
            EditorSettingsSectionSupport.resolveRenderWhitespaceLabel("boundary")
        ).isEqualTo(Strings.settings_render_whitespace_boundary)
        assertThat(
            EditorSettingsSectionSupport.resolveRenderWhitespaceLabel("all")
        ).isEqualTo(Strings.settings_render_whitespace_all)
        assertThat(
            EditorSettingsSectionSupport.resolveRenderWhitespaceLabel("unexpected")
        ).isEqualTo(Strings.settings_render_whitespace_none)

        assertThat(
            EditorSettingsSectionSupport.buildRenderWhitespaceOptions()
        ).containsExactly(
            EditorSettingsOptionSpec("none", Strings.settings_render_whitespace_none),
            EditorSettingsOptionSpec("boundary", Strings.settings_render_whitespace_boundary),
            EditorSettingsOptionSpec("all", Strings.settings_render_whitespace_all)
        ).inOrder()
    }

    @Test
    fun resolveNextTabSize_shouldCycleThroughCommonWidths() {
        assertThat(EditorSettingsSectionSupport.resolveNextTabSize(2)).isEqualTo(4)
        assertThat(EditorSettingsSectionSupport.resolveNextTabSize(4)).isEqualTo(8)
        assertThat(EditorSettingsSectionSupport.resolveNextTabSize(8)).isEqualTo(2)
        assertThat(EditorSettingsSectionSupport.resolveNextTabSize(3)).isEqualTo(2)
    }

    @Test
    fun rainbowBracketsHelpers_shouldValidateHintAndCoerceValues() {
        assertThat(
            EditorSettingsSectionSupport.validateRainbowBracketsMaxLines("0")
        ).isTrue()
        assertThat(
            EditorSettingsSectionSupport.validateRainbowBracketsMaxLines("200000")
        ).isTrue()
        assertThat(
            EditorSettingsSectionSupport.validateRainbowBracketsMaxLines("-1")
        ).isFalse()
        assertThat(
            EditorSettingsSectionSupport.validateRainbowBracketsMaxLines("200001")
        ).isFalse()
        assertThat(
            EditorSettingsSectionSupport.validateRainbowBracketsMaxLines("abc")
        ).isFalse()

        assertThat(
            EditorSettingsSectionSupport.resolveRainbowBracketsHintValue("120")
        ).isEqualTo(120)
        assertThat(
            EditorSettingsSectionSupport.resolveRainbowBracketsHintValue("abc")
        ).isEqualTo(0)

        assertThat(
            EditorSettingsSectionSupport.coerceRainbowBracketsMaxLines("300000", fallback = 50)
        ).isEqualTo(200000)
        assertThat(
            EditorSettingsSectionSupport.coerceRainbowBracketsMaxLines("abc", fallback = 50)
        ).isEqualTo(50)
    }
}
