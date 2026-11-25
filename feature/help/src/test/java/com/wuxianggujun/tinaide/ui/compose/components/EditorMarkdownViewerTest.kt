package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorMarkdownViewerTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun renderedMarkdownUsesReadOnlyEditorSurfacesAndCopiesTheWholeFence() {
        val copied = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                    EditorMarkdownViewer(
                        markdown = "# Lesson\n\n```cpp\nint value = 1;\n```\n\n```cmake\nproject(native_lab LANGUAGES CXX)\n```",
                        onCodeCopy = { copied += it },
                    )
                }
            }
        }
        compose.onNodeWithText("Lesson").assertExists()
        val viewers = compose.onAllNodesWithTag("tina-code-viewer").fetchSemanticsNodes()
        assertThat(viewers).hasSize(2)
        assertThat(viewers.map { it.config[SemanticsProperties.Text].single().text })
            .containsExactly("int value = 1;", "project(native_lab LANGUAGES CXX)").inOrder()
        assertThat(viewers.all { !it.config.contains(SemanticsActions.SetText) }).isTrue()
        compose.onAllNodesWithTag("markdown-code-copy")[0].performClick()
        assertThat(copied).containsExactly("int value = 1;")
    }
}
