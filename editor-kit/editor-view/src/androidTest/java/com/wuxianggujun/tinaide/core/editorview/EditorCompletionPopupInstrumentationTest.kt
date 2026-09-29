package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntOffset
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class EditorCompletionPopupInstrumentationTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun editorCompletionPopup_shouldExposeStableTagsOnDevice() {
        composeRule.setContent {
            EditorCompletionPopup(
                items = listOf(
                    EditorCompletionItem(
                        label = "printLine",
                        insertText = "printLine()",
                        kind = EditorCompletionKind.FUNCTION
                    ),
                    EditorCompletionItem(
                        label = "README.md",
                        insertText = "README.md",
                        kind = EditorCompletionKind.FILE
                    )
                ),
                selectedIndex = 0,
                query = "re",
                offset = IntOffset.Zero,
                widthPx = 320f,
                maxHeightPx = 180f,
                colorScheme = EditorColorScheme.builtinDark(),
                isLoading = false,
                onSelectedIndexChange = {},
                onSelect = {},
                onDismiss = {}
            )
        }

        assertNotNull(composeRule.onNodeWithTag(COMPLETION_POPUP_TAG).fetchSemanticsNode())
        assertNotNull(composeRule.onNodeWithTag(completionPopupRowTag(0)).fetchSemanticsNode())
        assertNotNull(composeRule.onNodeWithTag(completionPopupRowTag(1)).fetchSemanticsNode())
        assertNotNull(
            composeRule.onNodeWithTag(
                completionPopupKindIconTag(0),
                useUnmergedTree = true
            ).fetchSemanticsNode()
        )
        assertNotNull(
            composeRule.onNodeWithTag(
                completionPopupKindIconTag(1),
                useUnmergedTree = true
            ).fetchSemanticsNode()
        )
    }

    @Test
    fun editorCompletionPopup_shouldRemainSelectableAfterOffsetUpdateOnDevice() {
        var offsetX by mutableIntStateOf(0)
        var offsetY by mutableIntStateOf(0)
        var changedIndex: Int? = null
        var selectedLabel: String? = null

        composeRule.setContent {
            EditorCompletionPopup(
                items = listOf(
                    EditorCompletionItem(
                        label = "offset-probe-1",
                        insertText = "offset-probe-1()",
                        kind = EditorCompletionKind.FUNCTION
                    ),
                    EditorCompletionItem(
                        label = "offset-probe-2",
                        insertText = "offset-probe-2()",
                        kind = EditorCompletionKind.METHOD
                    )
                ),
                selectedIndex = 0,
                query = "of",
                offset = IntOffset(offsetX, offsetY),
                widthPx = 320f,
                maxHeightPx = 180f,
                colorScheme = EditorColorScheme.builtinDark(),
                isLoading = false,
                onSelectedIndexChange = { changedIndex = it },
                onSelect = { selectedLabel = it.label },
                onDismiss = {}
            )
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            offsetX = 48
            offsetY = 36
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(completionPopupRowTag(1)).performClick()

        assertEquals(1, changedIndex)
        assertEquals("offset-probe-2", selectedLabel)
    }
}
