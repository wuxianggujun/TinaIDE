package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.config.EditorThemeColorKey
import java.util.Locale
import org.junit.Test

class EditorColorSchemeTest {

    @Test
    fun colorOfSemantic_shouldUseConstantColorForEnumMembers() {
        val syntax = EditorColorScheme.builtinGray().syntax

        assertThat(
            syntax.colorOfSemantic(
                tokenType = SemanticTokenType.ENUM_MEMBER,
                tokenModifiers = emptySet()
            )
        ).isEqualTo(syntax.constant)
    }

    @Test
    fun fromThemeColorsAndExport_shouldCoverCustomizableColors() {
        val customized = EditorColorScheme.fromThemeColors(
            colors = mapOf(
                EditorThemeColorKey.EDITOR_WHITESPACE.wireName to "#80445566",
                EditorThemeColorKey.BRACKET_PAIR_GUIDE_ACTIVE.wireName to "#FF112233",
                EditorThemeColorKey.RAINBOW_BRACKET_1.wireName to "#FF010203",
                EditorThemeColorKey.SYNTAX_DEPRECATED.wireName to "#FFABCDEF"
            ),
            fallback = EditorColorScheme.builtinDark()
        )
        val exported = customized.toThemeColors()

        assertThat(exported[EditorThemeColorKey.EDITOR_WHITESPACE.wireName])
            .isEqualTo("#80445566")
        assertThat(exported[EditorThemeColorKey.BRACKET_PAIR_GUIDE_ACTIVE.wireName])
            .isEqualTo("#FF112233")
        assertThat(exported[EditorThemeColorKey.SYNTAX_DEPRECATED.wireName])
            .isEqualTo("#FFABCDEF")
        assertThat(customized.rainbowBracketColors).hasSize(6)
        assertThat(exported[EditorThemeColorKey.RAINBOW_BRACKET_1.wireName])
            .isEqualTo("#FF010203")
        assertThat(exported.keys)
            .containsAtLeastElementsIn(EditorThemeColorKey.entries.map(EditorThemeColorKey::wireName))
    }

    @Test
    fun fromThemeColorsAndExport_roundTripsEveryStableColorKey() {
        val colors = EditorThemeColorKey.entries.mapIndexed { index, key ->
            key.wireName to String.format(Locale.ROOT, "#FF%06X", index + 1)
        }.toMap()

        val exported = EditorColorScheme.fromThemeColors(
            colors = colors,
            fallback = EditorColorScheme.builtinGray()
        ).toThemeColors()

        assertThat(exported).containsAtLeastEntriesIn(colors)
    }
}
