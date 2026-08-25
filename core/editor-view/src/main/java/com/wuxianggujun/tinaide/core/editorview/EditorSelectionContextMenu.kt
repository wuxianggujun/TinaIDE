package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

internal const val SELECTION_CONTEXT_MENU_TAG = "editor_selection_context_menu"
internal const val SELECTION_CONTEXT_MENU_MORE_ACTION_TAG = "editor_selection_context_menu_action_more"
internal const val SELECTION_CONTEXT_MENU_MORE_MENU_TAG = "editor_selection_context_menu_more_menu"
internal const val SELECTION_CONTEXT_MENU_COPY_ACTION_TAG = "editor_selection_context_menu_action_copy"
internal const val SELECTION_CONTEXT_MENU_CUT_ACTION_TAG = "editor_selection_context_menu_action_cut"
internal const val SELECTION_CONTEXT_MENU_PASTE_ACTION_TAG = "editor_selection_context_menu_action_paste"
internal const val SELECTION_CONTEXT_MENU_SELECT_ALL_ACTION_TAG = "editor_selection_context_menu_action_select_all"
internal const val SELECTION_CONTEXT_MENU_GOTO_DEFINITION_ACTION_TAG = "editor_selection_context_menu_action_goto_definition"
internal const val SELECTION_CONTEXT_MENU_PEEK_DEFINITION_ACTION_TAG = "editor_selection_context_menu_action_peek_definition"
internal const val SELECTION_CONTEXT_MENU_FIND_REFERENCES_ACTION_TAG = "editor_selection_context_menu_action_find_references"
internal const val SELECTION_CONTEXT_MENU_GOTO_TYPE_DEFINITION_ACTION_TAG =
    "editor_selection_context_menu_action_goto_type_definition"
internal const val SELECTION_CONTEXT_MENU_GOTO_IMPLEMENTATION_ACTION_TAG =
    "editor_selection_context_menu_action_goto_implementation"
internal const val SELECTION_CONTEXT_MENU_CODE_ACTIONS_ACTION_TAG = "editor_selection_context_menu_action_code_actions"
internal const val SELECTION_CONTEXT_MENU_RENAME_SYMBOL_ACTION_TAG = "editor_selection_context_menu_action_rename_symbol"
internal const val SELECTION_CONTEXT_MENU_SWITCH_HEADER_SOURCE_ACTION_TAG =
    "editor_selection_context_menu_action_switch_header_source"
internal const val SELECTION_CONTEXT_MENU_HOVER_ACTION_TAG = "editor_selection_context_menu_action_hover"

private val selectionToolbarWidth = 200.dp
private val selectionToolbarHeight = 56.dp
private val selectionToolbarButtonSize = 48.dp

@Composable
internal fun EditorSelectionContextMenu(
    visible: Boolean,
    positionProvider: PopupPositionProvider,
    selectedText: String?,
    keyboardSelectedAction: EditorContextMenuActionId? = null,
    colorScheme: EditorColorScheme,
    hoverEnabled: Boolean,
    peekDefinitionEnabled: Boolean = false,
    gotoDefinitionEnabled: Boolean,
    findReferencesEnabled: Boolean,
    gotoTypeDefinitionEnabled: Boolean,
    gotoImplementationEnabled: Boolean,
    codeActionsEnabled: Boolean,
    renameSymbolEnabled: Boolean,
    switchHeaderSourceEnabled: Boolean,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPaste: () -> Unit,
    onSelectAll: () -> Unit,
    onPeekDefinition: () -> Unit = {},
    onGotoDefinition: () -> Unit,
    onFindReferences: () -> Unit,
    onGotoTypeDefinition: () -> Unit,
    onGotoImplementation: () -> Unit,
    onCodeActions: () -> Unit,
    onRenameSymbol: () -> Unit,
    onSwitchHeaderSource: () -> Unit,
    onHover: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    var moreMenuExpanded by remember { mutableStateOf(false) }
    val popupColors = rememberEditorPopupColors(colorScheme)
    val hasCodeActions = peekDefinitionEnabled ||
        gotoDefinitionEnabled ||
        findReferencesEnabled ||
        gotoTypeDefinitionEnabled ||
        gotoImplementationEnabled ||
        codeActionsEnabled ||
        renameSymbolEnabled ||
        switchHeaderSourceEnabled

    LaunchedEffect(keyboardSelectedAction) {
        when (keyboardSelectedAction) {
            EditorContextMenuActionId.SelectAll,
            EditorContextMenuActionId.PeekDefinition,
            EditorContextMenuActionId.GotoDefinition,
            EditorContextMenuActionId.FindReferences,
            EditorContextMenuActionId.GotoTypeDefinition,
            EditorContextMenuActionId.GotoImplementation,
            EditorContextMenuActionId.CodeActions,
            EditorContextMenuActionId.RenameSymbol,
            EditorContextMenuActionId.SwitchHeaderSource,
            EditorContextMenuActionId.Hover -> moreMenuExpanded = true

            EditorContextMenuActionId.Copy,
            EditorContextMenuActionId.Cut,
            EditorContextMenuActionId.Paste -> moreMenuExpanded = false

            null -> Unit
        }
    }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false)
    ) {
        EditorPopupSurface(
            colors = popupColors,
            modifier = Modifier
                .testTag(SELECTION_CONTEXT_MENU_TAG)
                .width(selectionToolbarWidth)
                .height(selectionToolbarHeight)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                EditorContextMenuIconAction(
                    title = stringResource(R.string.editor_context_menu_copy),
                    icon = Icons.Default.ContentCopy,
                    tag = SELECTION_CONTEXT_MENU_COPY_ACTION_TAG,
                    keyboardSelected = keyboardSelectedAction == EditorContextMenuActionId.Copy,
                    enabled = selectedText != null,
                    popupColors = popupColors,
                    modifier = Modifier.weight(1f),
                    onClick = onCopy
                )
                EditorContextMenuIconAction(
                    title = stringResource(R.string.editor_context_menu_cut),
                    icon = Icons.Default.ContentCut,
                    tag = SELECTION_CONTEXT_MENU_CUT_ACTION_TAG,
                    keyboardSelected = keyboardSelectedAction == EditorContextMenuActionId.Cut,
                    enabled = selectedText != null,
                    popupColors = popupColors,
                    modifier = Modifier.weight(1f),
                    onClick = onCut
                )
                EditorContextMenuIconAction(
                    title = stringResource(R.string.editor_context_menu_paste),
                    icon = Icons.Default.ContentPaste,
                    tag = SELECTION_CONTEXT_MENU_PASTE_ACTION_TAG,
                    keyboardSelected = keyboardSelectedAction == EditorContextMenuActionId.Paste,
                    popupColors = popupColors,
                    modifier = Modifier.weight(1f),
                    onClick = onPaste
                )
                EditorContextMenuMoreAction(
                    expanded = moreMenuExpanded,
                    keyboardSelectedAction = keyboardSelectedAction,
                    popupColors = popupColors,
                    hasCodeActions = hasCodeActions,
                    hoverEnabled = hoverEnabled,
                    peekDefinitionEnabled = peekDefinitionEnabled,
                    gotoDefinitionEnabled = gotoDefinitionEnabled,
                    findReferencesEnabled = findReferencesEnabled,
                    gotoTypeDefinitionEnabled = gotoTypeDefinitionEnabled,
                    gotoImplementationEnabled = gotoImplementationEnabled,
                    codeActionsEnabled = codeActionsEnabled,
                    renameSymbolEnabled = renameSymbolEnabled,
                    switchHeaderSourceEnabled = switchHeaderSourceEnabled,
                    modifier = Modifier.weight(1f),
                    onExpandedChange = { moreMenuExpanded = it },
                    onSelectAll = onSelectAll,
                    onPeekDefinition = onPeekDefinition,
                    onGotoDefinition = onGotoDefinition,
                    onFindReferences = onFindReferences,
                    onGotoTypeDefinition = onGotoTypeDefinition,
                    onGotoImplementation = onGotoImplementation,
                    onCodeActions = onCodeActions,
                    onRenameSymbol = onRenameSymbol,
                    onSwitchHeaderSource = onSwitchHeaderSource,
                    onHover = onHover
                )
            }
        }
    }
}

@Composable
private fun EditorContextMenuIconAction(
    title: String,
    icon: ImageVector,
    tag: String,
    keyboardSelected: Boolean,
    popupColors: EditorPopupColors,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier.fillMaxHeight(),
        contentAlignment = Alignment.Center
    ) {
        IconButton(
            modifier = Modifier
                .size(selectionToolbarButtonSize)
                .background(
                    color = if (keyboardSelected && enabled) {
                        popupColors.selectedSurfaceColor
                    } else {
                        popupColors.containerColor
                    },
                    shape = RoundedCornerShape(6.dp)
                )
                .testTag(tag),
            enabled = enabled,
            onClick = onClick
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (enabled) {
                    popupColors.primaryTextColor
                } else {
                    popupColors.secondaryTextColor.copy(alpha = 0.45f)
                }
            )
        }
    }
}

@Composable
private fun EditorContextMenuMoreAction(
    expanded: Boolean,
    keyboardSelectedAction: EditorContextMenuActionId?,
    popupColors: EditorPopupColors,
    hasCodeActions: Boolean,
    hoverEnabled: Boolean,
    peekDefinitionEnabled: Boolean,
    gotoDefinitionEnabled: Boolean,
    findReferencesEnabled: Boolean,
    gotoTypeDefinitionEnabled: Boolean,
    gotoImplementationEnabled: Boolean,
    codeActionsEnabled: Boolean,
    renameSymbolEnabled: Boolean,
    switchHeaderSourceEnabled: Boolean,
    modifier: Modifier = Modifier,
    onExpandedChange: (Boolean) -> Unit,
    onSelectAll: () -> Unit,
    onPeekDefinition: () -> Unit,
    onGotoDefinition: () -> Unit,
    onFindReferences: () -> Unit,
    onGotoTypeDefinition: () -> Unit,
    onGotoImplementation: () -> Unit,
    onCodeActions: () -> Unit,
    onRenameSymbol: () -> Unit,
    onSwitchHeaderSource: () -> Unit,
    onHover: () -> Unit
) {
    val moreTitle = stringResource(R.string.editor_context_menu_more)
    val runOverflowAction: (() -> Unit) -> Unit = { action ->
        onExpandedChange(false)
        action()
    }
    Box(
        modifier = modifier.fillMaxHeight(),
        contentAlignment = Alignment.Center
    ) {
        IconButton(
            modifier = Modifier
                .size(selectionToolbarButtonSize)
                .background(
                    color = if (expanded) popupColors.selectedSurfaceColor else popupColors.containerColor,
                    shape = RoundedCornerShape(6.dp)
                )
                .testTag(SELECTION_CONTEXT_MENU_MORE_ACTION_TAG),
            onClick = { onExpandedChange(!expanded) }
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = moreTitle,
                tint = popupColors.primaryTextColor
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            modifier = Modifier
                .testTag(SELECTION_CONTEXT_MENU_MORE_MENU_TAG)
                .widthIn(min = 196.dp, max = 280.dp)
                .heightIn(max = 320.dp),
            properties = PopupProperties(focusable = false),
            shape = RoundedCornerShape(editorPopupCornerRadius),
            containerColor = popupColors.containerColor,
            tonalElevation = 0.dp,
            shadowElevation = editorPopupElevation,
            border = BorderStroke(editorPopupBorderWidth, popupColors.borderColor)
        ) {
            EditorContextMenuOverflowAction(
                title = stringResource(R.string.editor_context_menu_select_all),
                icon = Icons.Default.SelectAll,
                tag = SELECTION_CONTEXT_MENU_SELECT_ALL_ACTION_TAG,
                keyboardSelected = keyboardSelectedAction == EditorContextMenuActionId.SelectAll,
                popupColors = popupColors,
                onClick = { runOverflowAction(onSelectAll) }
            )

            if (hasCodeActions) {
                EditorPopupDivider(colors = popupColors)
            }
            if (peekDefinitionEnabled) {
                EditorContextMenuOverflowAction(
                    title = stringResource(R.string.editor_context_menu_peek_definition),
                    tag = SELECTION_CONTEXT_MENU_PEEK_DEFINITION_ACTION_TAG,
                    keyboardSelected = keyboardSelectedAction == EditorContextMenuActionId.PeekDefinition,
                    popupColors = popupColors,
                    onClick = { runOverflowAction(onPeekDefinition) }
                )
            }
            if (gotoDefinitionEnabled) {
                EditorContextMenuOverflowAction(
                    title = stringResource(R.string.editor_context_menu_goto_definition),
                    tag = SELECTION_CONTEXT_MENU_GOTO_DEFINITION_ACTION_TAG,
                    keyboardSelected = keyboardSelectedAction == EditorContextMenuActionId.GotoDefinition,
                    popupColors = popupColors,
                    onClick = { runOverflowAction(onGotoDefinition) }
                )
            }
            if (findReferencesEnabled) {
                EditorContextMenuOverflowAction(
                    title = stringResource(R.string.editor_context_menu_find_references),
                    tag = SELECTION_CONTEXT_MENU_FIND_REFERENCES_ACTION_TAG,
                    keyboardSelected = keyboardSelectedAction == EditorContextMenuActionId.FindReferences,
                    popupColors = popupColors,
                    onClick = { runOverflowAction(onFindReferences) }
                )
            }
            if (gotoTypeDefinitionEnabled) {
                EditorContextMenuOverflowAction(
                    title = stringResource(R.string.editor_context_menu_goto_type_definition),
                    tag = SELECTION_CONTEXT_MENU_GOTO_TYPE_DEFINITION_ACTION_TAG,
                    keyboardSelected = keyboardSelectedAction == EditorContextMenuActionId.GotoTypeDefinition,
                    popupColors = popupColors,
                    onClick = { runOverflowAction(onGotoTypeDefinition) }
                )
            }
            if (gotoImplementationEnabled) {
                EditorContextMenuOverflowAction(
                    title = stringResource(R.string.editor_context_menu_goto_implementation),
                    tag = SELECTION_CONTEXT_MENU_GOTO_IMPLEMENTATION_ACTION_TAG,
                    keyboardSelected = keyboardSelectedAction == EditorContextMenuActionId.GotoImplementation,
                    popupColors = popupColors,
                    onClick = { runOverflowAction(onGotoImplementation) }
                )
            }
            if (codeActionsEnabled) {
                EditorContextMenuOverflowAction(
                    title = stringResource(R.string.editor_context_menu_code_actions),
                    tag = SELECTION_CONTEXT_MENU_CODE_ACTIONS_ACTION_TAG,
                    keyboardSelected = keyboardSelectedAction == EditorContextMenuActionId.CodeActions,
                    popupColors = popupColors,
                    onClick = { runOverflowAction(onCodeActions) }
                )
            }
            if (renameSymbolEnabled) {
                EditorContextMenuOverflowAction(
                    title = stringResource(R.string.editor_context_menu_rename_symbol),
                    tag = SELECTION_CONTEXT_MENU_RENAME_SYMBOL_ACTION_TAG,
                    keyboardSelected = keyboardSelectedAction == EditorContextMenuActionId.RenameSymbol,
                    popupColors = popupColors,
                    onClick = { runOverflowAction(onRenameSymbol) }
                )
            }
            if (switchHeaderSourceEnabled) {
                EditorContextMenuOverflowAction(
                    title = stringResource(R.string.editor_context_menu_switch_header_source),
                    tag = SELECTION_CONTEXT_MENU_SWITCH_HEADER_SOURCE_ACTION_TAG,
                    keyboardSelected = keyboardSelectedAction == EditorContextMenuActionId.SwitchHeaderSource,
                    popupColors = popupColors,
                    onClick = { runOverflowAction(onSwitchHeaderSource) }
                )
            }

            if (hoverEnabled) {
                EditorPopupDivider(colors = popupColors)
                EditorContextMenuOverflowAction(
                    title = stringResource(R.string.editor_context_menu_hover),
                    tag = SELECTION_CONTEXT_MENU_HOVER_ACTION_TAG,
                    keyboardSelected = keyboardSelectedAction == EditorContextMenuActionId.Hover,
                    popupColors = popupColors,
                    onClick = { runOverflowAction(onHover) }
                )
            }
        }
    }
}

@Composable
private fun EditorContextMenuOverflowAction(
    title: String,
    tag: String,
    keyboardSelected: Boolean,
    popupColors: EditorPopupColors,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val disabledColor = popupColors.secondaryTextColor.copy(alpha = 0.45f)
    DropdownMenuItem(
        text = { Text(title) },
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (keyboardSelected && enabled) {
                    popupColors.selectedSurfaceColor
                } else {
                    popupColors.containerColor
                }
            )
            .testTag(tag),
        enabled = enabled,
        leadingIcon = if (icon != null) {
            {
                Icon(
                    imageVector = icon,
                    contentDescription = null
                )
            }
        } else {
            null
        },
        colors = MenuDefaults.itemColors(
            textColor = popupColors.primaryTextColor,
            leadingIconColor = popupColors.secondaryTextColor,
            trailingIconColor = popupColors.secondaryTextColor,
            disabledTextColor = disabledColor,
            disabledLeadingIconColor = disabledColor,
            disabledTrailingIconColor = disabledColor
        )
    )
}
