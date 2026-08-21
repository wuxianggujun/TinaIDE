package com.wuxianggujun.tinaide.core.config

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CustomEditorThemeTest {

    @Test
    fun normalizeEditorThemeColor_acceptsRgbAndArgbAndRejectsInvalidInput() {
        assertThat(normalizeEditorThemeColor("#12abEF")).isEqualTo("#12ABEF")
        assertThat(normalizeEditorThemeColor("0x8012abef")).isEqualTo("#8012ABEF")
        assertThat(normalizeEditorThemeColor("12ABEF")).isEqualTo("#12ABEF")
        assertThat(normalizeEditorThemeColor("#12345")).isNull()
        assertThat(normalizeEditorThemeColor("#GG0000")).isNull()
    }

    @Test
    fun colorChannels_roundTripArgbAndClampValues() {
        val parsed = parseEditorThemeColorChannels("#80402010")

        assertThat(parsed).isEqualTo(
            EditorThemeColorChannels(alpha = 128, red = 64, green = 32, blue = 16)
        )
        assertThat(EditorThemeColorChannels(300, -1, 16, 512).toHexColor())
            .isEqualTo("#FF0010FF")
    }

    @Test
    fun sanitized_removesUnknownAndInvalidColors() {
        val theme = CustomEditorTheme(
            base = EditorThemeBase.DARK,
            colors = mapOf(
                EditorThemeColorKey.SYNTAX_KEYWORD.wireName to "#123456",
                EditorThemeColorKey.SYNTAX_STRING.wireName to "invalid",
                "unsupported.color" to "#FFFFFF"
            )
        ).sanitized()

        assertThat(theme.base).isEqualTo(EditorThemeBase.DARK)
        assertThat(theme.colors).containsExactly(
            EditorThemeColorKey.SYNTAX_KEYWORD.wireName,
            "#123456"
        )
    }

    @Test
    fun codec_roundTripsSanitizedThemeAndFallsBackForMalformedJson() {
        val original = CustomEditorTheme(
            base = EditorThemeBase.LIGHT,
            colors = mapOf(
                EditorThemeColorKey.EDITOR_BACKGROUND.wireName to "#FF102030",
                EditorThemeColorKey.DIAGNOSTIC_ERROR.wireName to "#AAFF0000"
            )
        )

        assertThat(CustomEditorThemeCodec.decode(CustomEditorThemeCodec.encode(original)))
            .isEqualTo(original)
        assertThat(CustomEditorThemeCodec.decode("not-json"))
            .isEqualTo(CustomEditorTheme())
    }
}
