package com.wuxianggujun.tinaide.plugin

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class ThemeConfigSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun tokenColorScope_acceptsStringAndArrayForms() {
        val stringScope = json.decodeFromString<ThemeConfig>(
            """{"name":"String","tokenColors":[{"scope":"keyword","settings":{"foreground":"#FF0000"}}]}"""
        )
        val arrayScope = json.decodeFromString<ThemeConfig>(
            """{"name":"Array","tokenColors":[{"scope":["comment","string"],"settings":{"foreground":"#00FF00"}}]}"""
        )
        val defaultScope = json.decodeFromString<ThemeConfig>(
            """{"name":"Default","tokenColors":[{"settings":{"foreground":"#CCCCCC"}}]}"""
        )
        val nullScope = json.decodeFromString<ThemeConfig>(
            """{"name":"Null","tokenColors":[{"scope":null,"settings":{"foreground":"#CCCCCC"}}]}"""
        )

        assertThat(stringScope.colors).isEmpty()
        assertThat(stringScope.tokenColors.orEmpty().single().scope).containsExactly("keyword")
        assertThat(arrayScope.tokenColors.orEmpty().single().scope)
            .containsExactly("comment", "string")
            .inOrder()
        assertThat(defaultScope.tokenColors.orEmpty().single().scope).isEmpty()
        assertThat(nullScope.tokenColors.orEmpty().single().scope).isEmpty()
    }
}
