package com.wuxianggujun.tinaide.editor.theme

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.config.EditorThemeColorKey
import com.wuxianggujun.tinaide.plugin.ThemeConfig
import com.wuxianggujun.tinaide.plugin.TokenColor
import com.wuxianggujun.tinaide.plugin.TokenSettings
import org.junit.Test

class PluginThemeColorResolverTest {

    @Test
    fun resolve_mapsTextMateScopesAndLetsExplicitColorsWin() {
        val theme = ThemeConfig(
            name = "Test",
            colors = mapOf(EditorThemeColorKey.SYNTAX_KEYWORD.wireName to "#FFFFFF"),
            tokenColors = listOf(
                TokenColor(
                    scope = listOf("keyword", "entity.name.function"),
                    settings = TokenSettings(foreground = "#112233")
                ),
                TokenColor(
                    scope = listOf("variable.other.property, constant.numeric"),
                    settings = TokenSettings(foreground = "#445566")
                )
            )
        )

        val resolved = PluginThemeColorResolver.resolve(theme)

        assertThat(resolved[EditorThemeColorKey.SYNTAX_KEYWORD.wireName]).isEqualTo("#FFFFFF")
        assertThat(resolved[EditorThemeColorKey.SYNTAX_FUNCTION.wireName]).isEqualTo("#112233")
        assertThat(resolved[EditorThemeColorKey.SYNTAX_PROPERTY.wireName]).isEqualTo("#445566")
        assertThat(resolved[EditorThemeColorKey.SYNTAX_NUMBER.wireName]).isEqualTo("#445566")
    }

    @Test
    fun resolve_ignoresBlankAndUnknownScopes() {
        val resolved = PluginThemeColorResolver.resolve(
            ThemeConfig(
                name = "Test",
                tokenColors = listOf(
                    TokenColor(listOf("meta.unknown"), TokenSettings(foreground = "#123456")),
                    TokenColor(listOf("comment"), TokenSettings(foreground = "  "))
                )
            )
        )

        assertThat(resolved).isEmpty()
    }

    @Test
    fun resolve_supportsDefaultForegroundAndCompoundSelectorsWithoutPartialMatches() {
        val resolved = PluginThemeColorResolver.resolve(
            ThemeConfig(
                name = "Test",
                tokenColors = listOf(
                    TokenColor(emptyList(), TokenSettings(foreground = "#AABBCC")),
                    TokenColor(
                        listOf("source.kotlin meta.function entity.name.function"),
                        TokenSettings(foreground = "#112233")
                    ),
                    TokenColor(listOf("meta.commentary"), TokenSettings(foreground = "#445566"))
                )
            )
        )

        assertThat(resolved[EditorThemeColorKey.EDITOR_FOREGROUND.wireName]).isEqualTo("#AABBCC")
        assertThat(resolved[EditorThemeColorKey.SYNTAX_FUNCTION.wireName]).isEqualTo("#112233")
        assertThat(resolved).doesNotContainValue("#445566")
    }

    @Test
    fun resolve_blankExplicitColorDoesNotHideInferredColor() {
        val resolved = PluginThemeColorResolver.resolve(
            ThemeConfig(
                name = "Test",
                colors = mapOf(EditorThemeColorKey.SYNTAX_COMMENT.wireName to "  "),
                tokenColors = listOf(
                    TokenColor(listOf("comment"), TokenSettings(foreground = "#123456"))
                )
            )
        )

        assertThat(resolved[EditorThemeColorKey.SYNTAX_COMMENT.wireName])
            .isEqualTo("#123456")
    }
}
