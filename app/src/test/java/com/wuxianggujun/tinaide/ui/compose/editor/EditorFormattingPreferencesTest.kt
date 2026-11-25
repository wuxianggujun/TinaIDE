package com.wuxianggujun.tinaide.ui.compose.editor

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class EditorFormattingPreferencesTest {
    @Test
    fun onlyFormattingConfigurationsInSourceAncestorsTriggerARefresh() {
        val root = File("workspace").absoluteFile
        val source = root.resolve("project/src/main.cpp")
        assertThat(isEditorFormattingConfig(root.resolve("project/.clang-format"), source)).isTrue()
        assertThat(isEditorFormattingConfig(root.resolve("project/src/_clang-format"), source)).isTrue()
        assertThat(isEditorFormattingConfig(root.resolve(".clang-format"), source)).isTrue()
        assertThat(isEditorFormattingConfig(root.resolve("other/.clang-format"), source)).isFalse()
        assertThat(isEditorFormattingConfig(root.resolve("project/.clangd"), source)).isFalse()
    }
}
