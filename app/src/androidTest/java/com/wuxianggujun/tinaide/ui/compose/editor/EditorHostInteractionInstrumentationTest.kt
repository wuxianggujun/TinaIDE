package com.wuxianggujun.tinaide.ui.compose.editor

import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wuxianggujun.tinaide.core.config.AppTheme
import com.wuxianggujun.tinaide.core.config.ThemeManager
import com.wuxianggujun.tinaide.core.editorview.EditorColorScheme
import com.wuxianggujun.tinaide.core.editorview.EditorConfig
import com.wuxianggujun.tinaide.core.editorview.EditorState
import com.wuxianggujun.tinaide.core.editorview.TinaEditor
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import com.wuxianggujun.tinaide.ui.compose.components.MarkdownViewer
import com.wuxianggujun.tinaide.ui.theme.TinaIDETheme
import com.wuxianggujun.tinaide.ui.regressionEvidenceDirectory
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual host theme and Markdown slot with the production editor. */
@RunWith(AndroidJUnit4::class)
class EditorHostInteractionInstrumentationTest {
    @get:Rule val composeRule = createComposeRule()
    private val buffer = RopeTextBuffer("abcdefghij\nint total = 42;\n" + "// more source\n".repeat(50))
    private val state = EditorState(buffer, config = EditorConfig(wordWrap = false)).apply { typeface = Typeface.MONOSPACE }
    private val originalTheme = ThemeManager.getCurrentTheme()
    private var mounted by mutableStateOf(true)
    private var mountedOnce = false
    private var hoverColors: List<Pair<Color, Color>> = emptyList()

    @After fun tearDown() {
        if (mountedOnce) {
            composeRule.runOnIdle { mounted = false; ThemeManager.setTheme(originalTheme) }
            composeRule.waitForIdle()
        }
        buffer.close()
    }

    @Test fun hostMarkdownAndSearch_remainReadableAndPreserveSelectionAcrossOppositeThemes() {
        state.onRequestHover = { "**Hover documentation**\n\n```cpp\nint total = 42;\n```\n\nA readable explanation." }
        composeRule.setContent {
            if (mounted) TinaIDETheme {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    TinaEditor(state, Modifier.fillMaxSize().testTag("host_editor"), hoverContent = { markdown, modifier, onLink, onCopy ->
                        val colors = MaterialTheme.colorScheme
                        hoverColors = listOf(colors.onSurface to colors.surface,
                            colors.onSurface to colors.surfaceContainerHighest, colors.primary to colors.surface)
                        MarkdownViewer(markdown, modifier, PaddingValues(horizontal = 12.dp, vertical = 10.dp), onLink, onCopy)
                    })
                }
            }
        }
        mountedOnce = true
        for ((name, editorTheme, hostTheme) in listOf(
            Triple("dark", EditorColorScheme.builtinDark(), AppTheme.LIGHT),
            Triple("light", EditorColorScheme.builtinLight(), AppTheme.DARK)
        )) {
            composeRule.runOnIdle {
                ThemeManager.setTheme(hostTheme)
                state.colorScheme = editorTheme
                state.moveCursorTo(0)
            }
            longPressFirstWord()
            composeRule.onNodeWithTag("editor_selection_context_menu_action_select_all").assertIsDisplayed()
            val original = state.selectionSet
            assertEquals("abcdefghij", state.selectedText())
            composeRule.onNodeWithTag("editor_selection_context_menu_action_more").performClick()
            composeRule.onNodeWithTag("editor_selection_context_menu_action_Find").performClick()
            composeRule.onNodeWithTag("editor_find_query").assertIsFocused()
            composeRule.waitUntil(5_000) { state.find.isCurrent }
            screenshot("host-find-$name")
            composeRule.onNodeWithTag("editor_find_query").performTextReplacement("abc")
            composeRule.waitUntil(5_000) { state.find.isCurrent }
            composeRule.onNodeWithTag("editor_find_close").performClick()
            composeRule.runOnIdle { assertEquals(original, state.selectionSet) }
            SystemClock.sleep(300L)
            longPressFirstWord()
            composeRule.onNodeWithTag("editor_selection_context_menu_action_more").performClick()
            composeRule.onNodeWithTag("editor_selection_context_menu_action_hover").performClick()
            composeRule.waitUntil(5_000) { hoverColors.isNotEmpty() }
            composeRule.onNodeWithText("Hover documentation").assertIsDisplayed()
            composeRule.onNodeWithText("int total = 42;").assertIsDisplayed()
            for ((foreground, background) in hoverColors) {
                val text = foreground.compositeOver(background).luminance()
                val surface = background.luminance()
                assertTrue((maxOf(text, surface) + 0.05f) / (minOf(text, surface) + 0.05f) >= 4.5f)
            }
            screenshot("host-markdown-$name")
            composeRule.onNodeWithText("Hover documentation").assertIsDisplayed()
            // Clicking in the document dismisses the hover through the production gesture path.
            composeRule.onNodeWithTag("host_editor").performTouchInput {
                down(Offset(width * 0.8f, height * 0.8f)); up()
            }
            composeRule.runOnIdle { hoverColors = emptyList() }
        }
    }

    private fun longPressFirstWord() {
        composeRule.onNodeWithTag("host_editor").performTouchInput {
            longClick(Offset(state.contentStartXPx + state.charWidthPx * 3.5f, state.lineHeightPx * 0.5f))
        }
    }

    private fun screenshot(name: String) {
        composeRule.waitForIdle()
        SystemClock.sleep(250L)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = regressionEvidenceDirectory(instrumentation.targetContext)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
