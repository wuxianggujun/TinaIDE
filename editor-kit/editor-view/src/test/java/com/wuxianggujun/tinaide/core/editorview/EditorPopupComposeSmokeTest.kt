package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.editorlsp.SignatureHelpResult
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorPopupComposeSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun editorCompletionPopup_shouldRenderItemsAndDispatchSelectionCallbacks() {
        var changedIndex: Int? = null
        var selectedLabel: String? = null

        composeRule.setContent {
            EditorCompletionPopup(
                items = listOf(
                    EditorCompletionItem(
                        label = "printLine",
                        insertText = "printLine()",
                        kind = EditorCompletionKind.FUNCTION
                    ),
                    EditorCompletionItem(
                        label = "println",
                        detail = "kotlin.io",
                        kind = EditorCompletionKind.METHOD
                    )
                ),
                selectedIndex = 0,
                query = "pl",
                offset = IntOffset.Zero,
                widthPx = 320f,
                maxHeightPx = 180f,
                colorScheme = EditorColorScheme.builtinDark(),
                isLoading = true,
                onSelectedIndexChange = { changedIndex = it },
                onSelect = { selectedLabel = it.label },
                onDismiss = {}
            )
        }

        assertThat(composeRule.onAllNodesWithText("printLine").fetchSemanticsNodes()).isNotEmpty()
        assertThat(composeRule.onAllNodesWithText("→ printLine()").fetchSemanticsNodes()).isNotEmpty()
        composeRule.onNodeWithText("println").performClick()

        assertThat(changedIndex).isEqualTo(1)
        assertThat(selectedLabel).isEqualTo("println")
    }

    @Test
    fun editorCompletionPopup_shouldExposeStableTagsForPopupRowsAndKindIcons() {
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

        assertThat(composeRule.onNodeWithTag(COMPLETION_POPUP_TAG).fetchSemanticsNode()).isNotNull()
        assertThat(composeRule.onNodeWithTag(completionPopupRowTag(0)).fetchSemanticsNode()).isNotNull()
        assertThat(composeRule.onNodeWithTag(completionPopupRowTag(1)).fetchSemanticsNode()).isNotNull()
        assertThat(
            composeRule.onNodeWithTag(
                completionPopupKindIconTag(0),
                useUnmergedTree = true
            ).fetchSemanticsNode()
        ).isNotNull()
        assertThat(
            composeRule.onNodeWithTag(
                completionPopupKindIconTag(1),
                useUnmergedTree = true
            ).fetchSemanticsNode()
        ).isNotNull()
    }

    @Test
    fun editorCompletionPopup_shouldScrollSelectedItemIntoView() {
        val items = List(18) { index ->
            EditorCompletionItem(
                label = "candidate-$index",
                insertText = "candidate-$index()",
                kind = EditorCompletionKind.FUNCTION
            )
        }
        var selectedIndex by mutableIntStateOf(0)

        composeRule.setContent {
            EditorCompletionPopup(
                items = items,
                selectedIndex = selectedIndex,
                query = "ca",
                offset = IntOffset.Zero,
                widthPx = 320f,
                maxHeightPx = 120f,
                colorScheme = EditorColorScheme.builtinDark(),
                isLoading = false,
                onSelectedIndexChange = {},
                onSelect = {},
                onDismiss = {}
            )
        }
        composeRule.waitForIdle()
        assertThat(
            composeRule.onAllNodesWithText("candidate-14").fetchSemanticsNodes()
        ).isEmpty()

        composeRule.runOnIdle {
            selectedIndex = 14
        }
        composeRule.waitForIdle()

        assertThat(
            composeRule.onAllNodesWithText("candidate-14").fetchSemanticsNodes()
        ).isNotEmpty()
    }

    @Test
    fun editorCompletionPopup_shouldRemainInteractiveWhenOffsetChanges() {
        var offsetX by mutableIntStateOf(0)
        var offsetY by mutableIntStateOf(0)
        var selectedLabel: String? = null

        composeRule.setContent {
            EditorCompletionPopup(
                items = listOf(
                    EditorCompletionItem(
                        label = "offset-probe",
                        insertText = "offset-probe()",
                        kind = EditorCompletionKind.FUNCTION
                    )
                ),
                selectedIndex = 0,
                query = "of",
                offset = IntOffset(offsetX, offsetY),
                widthPx = 320f,
                maxHeightPx = 180f,
                colorScheme = EditorColorScheme.builtinDark(),
                isLoading = false,
                onSelectedIndexChange = {},
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

        composeRule.onNodeWithText("offset-probe").performClick()

        assertThat(selectedLabel).isEqualTo("offset-probe")
    }

    @Test
    fun editorCompletionPopup_shouldRemainInteractiveWhenColorSchemeChanges() {
        var colorScheme by mutableStateOf(EditorColorScheme.builtinDark())
        var selectedLabel: String? = null

        composeRule.setContent {
            EditorCompletionPopup(
                items = listOf(
                    EditorCompletionItem(
                        label = "theme-probe",
                        insertText = "theme-probe()",
                        kind = EditorCompletionKind.FUNCTION
                    )
                ),
                selectedIndex = 0,
                query = "th",
                offset = IntOffset.Zero,
                widthPx = 320f,
                maxHeightPx = 180f,
                colorScheme = colorScheme,
                isLoading = false,
                onSelectedIndexChange = {},
                onSelect = { selectedLabel = it.label },
                onDismiss = {}
            )
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            colorScheme = EditorColorScheme.builtinGray()
        }
        composeRule.waitForIdle()

        assertThat(composeRule.onAllNodesWithText("theme-probe").fetchSemanticsNodes()).isNotEmpty()
        composeRule.onNodeWithText("theme-probe").performClick()

        assertThat(selectedLabel).isEqualTo("theme-probe")
    }

    @Test
    fun editorSignatureHelpPopup_shouldRenderSelectedPreviewAndDispatchActions() {
        var clickedSignatureIndex: Int? = null
        var cycleDelta: Int? = null

        composeRule.setContent {
            EditorSignatureHelpPopup(
                result = SignatureHelpResult(
                    signatures = listOf(
                        "foo(Int count, String label)",
                        "foo(Int count, Boolean enabled)"
                    ),
                    activeSignature = 0,
                    activeParameter = 1
                ),
                displayedSignatureIndex = 1,
                offset = IntOffset.Zero,
                widthPx = 360f,
                minHeightPx = 96f,
                maxHeightPx = 240f,
                colorScheme = EditorColorScheme.builtinDark(),
                isLoading = false,
                onSelectSignature = { clickedSignatureIndex = it },
                onCycleSignature = { cycleDelta = it },
                onDismiss = {}
            )
        }

        assertThat(composeRule.onAllNodesWithText("2/2").fetchSemanticsNodes()).isNotEmpty()
        assertThat(composeRule.onAllNodesWithText("Boolean enabled").fetchSemanticsNodes()).isNotEmpty()
        assertThat(composeRule.onAllNodesWithText("String label").fetchSemanticsNodes()).isEmpty()
        composeRule.onNodeWithText("foo(Int count, String label)").performClick()
        composeRule.onNodeWithContentDescription("Previous signature").performClick()

        assertThat(clickedSignatureIndex).isEqualTo(0)
        assertThat(cycleDelta).isEqualTo(-1)
    }

    @Test
    fun editorSignatureHelpPopup_shouldExposeOverflowIndicatorsAndJumpAcrossHiddenSlices() {
        var selectedSignatureIndex: Int? = null

        composeRule.setContent {
            EditorSignatureHelpPopup(
                result = SignatureHelpResult(
                    signatures = listOf(
                        "foo(Int count, String first)",
                        "foo(Int count, String second)",
                        "foo(Int count, String third)",
                        "foo(Int count, String fourth)",
                        "foo(Int count, String fifth)"
                    ),
                    activeSignature = 0,
                    activeParameter = 1
                ),
                displayedSignatureIndex = 4,
                offset = IntOffset.Zero,
                widthPx = 360f,
                minHeightPx = 96f,
                maxHeightPx = 240f,
                colorScheme = EditorColorScheme.builtinDark(),
                isLoading = false,
                onSelectSignature = { selectedSignatureIndex = it },
                onCycleSignature = {},
                onDismiss = {}
            )
        }

        assertThat(composeRule.onAllNodesWithText("↑ +2").fetchSemanticsNodes()).isNotEmpty()
        assertThat(composeRule.onAllNodesWithText("String fifth").fetchSemanticsNodes()).isNotEmpty()
        assertThat(composeRule.onAllNodesWithText("String first").fetchSemanticsNodes()).isEmpty()
        composeRule.onNodeWithText("↑ +2").performClick()

        assertThat(selectedSignatureIndex).isEqualTo(1)
    }

    @Test
    fun editorSignatureHelpPopup_shouldRemainInteractiveWhenOffsetChanges() {
        var offsetX by mutableIntStateOf(0)
        var offsetY by mutableIntStateOf(0)
        var clickedSignatureIndex: Int? = null

        composeRule.setContent {
            EditorSignatureHelpPopup(
                result = SignatureHelpResult(
                    signatures = listOf(
                        "sig(Int count, String offsetProbe)",
                        "sig(Int count, Boolean enabled)"
                    ),
                    activeSignature = 0,
                    activeParameter = 1
                ),
                displayedSignatureIndex = 0,
                offset = IntOffset(offsetX, offsetY),
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

        composeRule.runOnIdle {
            offsetX = 52
            offsetY = 28
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("sig(Int count, String offsetProbe)").performClick()

        assertThat(clickedSignatureIndex).isEqualTo(0)
    }

    @Test
    fun editorSignatureHelpPopup_shouldRemainInteractiveWhenDensityChanges() {
        var densityScale by mutableFloatStateOf(1f)
        var clickedSignatureIndex: Int? = null

        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(densityScale)) {
                EditorSignatureHelpPopup(
                    result = SignatureHelpResult(
                        signatures = listOf(
                            "sig(Int count, String densityProbe)",
                            "sig(Int count, Boolean enabled)"
                        ),
                        activeSignature = 0,
                        activeParameter = 1
                    ),
                    displayedSignatureIndex = 0,
                    offset = IntOffset.Zero,
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
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            densityScale = 1.6f
        }
        composeRule.waitForIdle()

        assertThat(
            composeRule.onAllNodesWithText("sig(Int count, String densityProbe)")
                .fetchSemanticsNodes()
        ).isNotEmpty()
        composeRule.onNodeWithText("sig(Int count, String densityProbe)").performClick()

        assertThat(clickedSignatureIndex).isEqualTo(0)
    }

    @Test
    fun editorSelectionContextMenu_shouldExposePrimaryActionsWithoutMovingWhenMoreOpens() {
        val events = mutableListOf<String>()

        composeRule.setContent {
            EditorSelectionContextMenu(
                visible = true,
                positionProvider = FixedPopupPositionProvider,
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
                onCopy = { events += "copy" },
                onCut = { events += "cut" },
                onPaste = { events += "paste" },
                onSelectAll = { events += "selectAll" },
                onPeekDefinition = { events += "peekDefinition" },
                onGotoDefinition = { events += "gotoDefinition" },
                onFindReferences = { events += "findReferences" },
                onGotoTypeDefinition = { events += "gotoTypeDefinition" },
                onGotoImplementation = { events += "gotoImplementation" },
                onCodeActions = { events += "codeActions" },
                onRenameSymbol = { events += "renameSymbol" },
                onSwitchHeaderSource = { events += "switchHeaderSource" },
                onHover = { events += "hover" },
                onDismiss = { events += "dismiss" }
            )
        }

        assertThat(
            composeRule.onNodeWithTag(SELECTION_CONTEXT_MENU_TAG).fetchSemanticsNode()
        ).isNotNull()
        val toolbarBoundsBeforeMore = composeRule
            .onNodeWithTag(SELECTION_CONTEXT_MENU_TAG)
            .fetchSemanticsNode()
            .boundsInRoot

        composeRule.onNodeWithTag(SELECTION_CONTEXT_MENU_COPY_ACTION_TAG).performClick()
        composeRule.onNodeWithTag(SELECTION_CONTEXT_MENU_MORE_ACTION_TAG).performClick()
        val toolbarBoundsAfterMore = composeRule
            .onNodeWithTag(SELECTION_CONTEXT_MENU_TAG)
            .fetchSemanticsNode()
            .boundsInRoot
        composeRule.onNodeWithTag(SELECTION_CONTEXT_MENU_HOVER_ACTION_TAG).performClick()

        assertThat(toolbarBoundsAfterMore).isEqualTo(toolbarBoundsBeforeMore)
        assertThat(events).containsExactly("copy", "hover").inOrder()
    }

    @Test
    fun editorSelectionContextMenu_shouldKeepMoreMenuCollapsedOnInitialLongPress() {
        val events = mutableListOf<String>()

        composeRule.setContent {
            TestSelectionContextMenu(
                selectedText = null,
                gotoDefinitionEnabled = true,
                onGotoDefinition = { events += "gotoDefinition" }
            )
        }
        composeRule.waitForIdle()

        assertThat(
            composeRule.onAllNodesWithTag(SELECTION_CONTEXT_MENU_GOTO_DEFINITION_ACTION_TAG).fetchSemanticsNodes()
        ).isEmpty()

        composeRule.onNodeWithTag(SELECTION_CONTEXT_MENU_MORE_ACTION_TAG).performClick()
        composeRule.onNodeWithTag(SELECTION_CONTEXT_MENU_GOTO_DEFINITION_ACTION_TAG).performClick()

        assertThat(events).containsExactly("gotoDefinition")
    }

    @Test
    fun editorSelectionContextMenu_shouldOpenMoreMenuForKeyboardSelectedCodeAction() {
        composeRule.setContent {
            TestSelectionContextMenu(
                keyboardSelectedAction = EditorContextMenuActionId.GotoDefinition,
                gotoDefinitionEnabled = true
            )
        }
        composeRule.waitForIdle()

        assertThat(
            composeRule.onAllNodesWithTag(SELECTION_CONTEXT_MENU_GOTO_DEFINITION_ACTION_TAG).fetchSemanticsNodes()
        ).isNotEmpty()
    }

    @Composable
    private fun TestSelectionContextMenu(
        selectedText: String? = "selected-text",
        keyboardSelectedAction: EditorContextMenuActionId? = null,
        gotoDefinitionEnabled: Boolean = false,
        onGotoDefinition: () -> Unit = {}
    ) {
        EditorSelectionContextMenu(
            visible = true,
            positionProvider = FixedPopupPositionProvider,
            selectedText = selectedText,
            keyboardSelectedAction = keyboardSelectedAction,
            colorScheme = EditorColorScheme.builtinDark(),
            hoverEnabled = false,
            peekDefinitionEnabled = false,
            gotoDefinitionEnabled = gotoDefinitionEnabled,
            findReferencesEnabled = false,
            gotoTypeDefinitionEnabled = false,
            gotoImplementationEnabled = false,
            codeActionsEnabled = false,
            renameSymbolEnabled = false,
            switchHeaderSourceEnabled = false,
            onCopy = {},
            onCut = {},
            onPaste = {},
            onSelectAll = {},
            onPeekDefinition = {},
            onGotoDefinition = onGotoDefinition,
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

    private object FixedPopupPositionProvider : PopupPositionProvider {
        override fun calculatePosition(
            anchorBounds: IntRect,
            windowSize: IntSize,
            layoutDirection: LayoutDirection,
            popupContentSize: IntSize
        ): IntOffset = IntOffset.Zero
    }
}
