package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
class EditorSharedPopupInstrumentationTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun editorSignatureHelpPopup_shouldExposeStableTagsAndRemainSelectableAfterOffsetUpdateOnDevice() {
        var offset by mutableStateOf(IntOffset.Zero)
        var clickedSignatureIndex: Int? = null

        composeRule.setContent {
            EditorSignatureHelpPopup(
                result = com.wuxianggujun.tinaide.core.editorlsp.SignatureHelpResult(
                    signatures = listOf(
                        "sig(Int count, String first)",
                        "sig(Int count, Boolean enabled)"
                    ),
                    activeSignature = 0,
                    activeParameter = 1
                ),
                displayedSignatureIndex = 0,
                offset = offset,
                widthPx = 360f,
                minHeightPx = 96f,
                maxHeightPx = 240f,
                colorScheme = EditorColorScheme.builtinDark(),
                isLoading = false,
                onSelectSignature = { clickedSignatureIndex = it },
                onCycleSignature = {},
                onDismiss = {}
            )
        }
        composeRule.waitForIdle()

        assertNotNull(composeRule.onNodeWithTag(SIGNATURE_HELP_POPUP_TAG).fetchSemanticsNode())
        assertNotNull(composeRule.onNodeWithTag(signatureHelpPopupRowTag(0)).fetchSemanticsNode())
        assertNotNull(composeRule.onNodeWithTag(signatureHelpPopupRowTag(1)).fetchSemanticsNode())

        composeRule.runOnIdle {
            offset = IntOffset(56, 32)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("sig(Int count, Boolean enabled)").performClick()

        assertEquals(1, clickedSignatureIndex)
    }

    @Test
    fun editorSelectionContextMenu_shouldRemainInteractiveAfterAnchorUpdateOnDevice() {
        var anchor by mutableStateOf(IntOffset(24, 120))
        var selectAllClicks = 0

        composeRule.setContent {
            val positionProvider = remember(anchor) {
                ContextMenuPopupPositionProvider(
                    anchorInWindowPx = anchor,
                    imeBottomInsetPx = 0,
                    marginPx = 8
                )
            }
            EditorSelectionContextMenu(
                visible = true,
                positionProvider = positionProvider,
                selectedText = "selected-text",
                colorScheme = EditorColorScheme.builtinDark(),
                hoverEnabled = true,
                peekDefinitionEnabled = false,
                gotoDefinitionEnabled = false,
                findReferencesEnabled = false,
                gotoTypeDefinitionEnabled = false,
                gotoImplementationEnabled = false,
                codeActionsEnabled = false,
                renameSymbolEnabled = false,
                switchHeaderSourceEnabled = false,
                onCopy = {},
                onCut = {},
                onPaste = {},
                onSelectAll = { selectAllClicks++ },
                onPeekDefinition = {},
                onGotoDefinition = {},
                onFindReferences = {},
                onGotoTypeDefinition = {},
                onGotoImplementation = {},
                onCodeActions = {},
                onRenameSymbol = {},
                onSwitchHeaderSource = {},
                onHover = {},
                onDismiss = {}
            )
        }
        composeRule.waitForIdle()

        assertNotNull(composeRule.onNodeWithTag(SELECTION_CONTEXT_MENU_TAG).fetchSemanticsNode())

        composeRule.runOnIdle {
            anchor = IntOffset(88, 196)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SELECTION_CONTEXT_MENU_MORE_ACTION_TAG).performClick()
        composeRule.onNodeWithTag(SELECTION_CONTEXT_MENU_SELECT_ALL_ACTION_TAG).performClick()

        assertEquals(1, selectAllClicks)
    }
}
