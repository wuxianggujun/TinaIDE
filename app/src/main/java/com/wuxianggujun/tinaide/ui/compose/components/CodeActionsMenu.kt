package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.lsp.LspCodeActionService

/**
 * 代码操作菜单
 */
@Composable
fun CodeActionsMenu(
    actions: List<LspCodeActionService.CodeActionItem>,
    isLoading: Boolean,
    onActionClick: (LspCodeActionService.CodeActionItem) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            TinaDialogTitleText(stringResource(Strings.code_actions_title))
        },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard(
                    contentPadding = PaddingValues(0.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    if (isLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    } else if (actions.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(Strings.code_actions_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(actions) { action ->
                                CodeActionItem(
                                    action = action,
                                    onClick = { onActionClick(action) }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        },
        modifier = modifier
    )
}

@Composable
private fun CodeActionItem(
    action: LspCodeActionService.CodeActionItem,
    onClick: () -> Unit
) {
    val enabled = action.isEnabled
    val disabledReason = action.disabledReason
    val icon = when {
        action.kind?.contains("quickfix") == true -> Icons.Default.Build
        action.kind?.contains("refactor") == true -> Icons.Default.Edit
        action.isPreferred -> Icons.Default.Star
        else -> Icons.Default.Build
    }

    TinaDialogCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        contentPadding = PaddingValues(0.dp),
        color = MaterialTheme.colorScheme.surface,
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        ListItem(
            headlineContent = {
                Text(
                    text = action.title,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    }
                )
            },
            supportingContent = when {
                disabledReason != null -> {
                    {
                        Text(
                            text = disabledReason,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2
                        )
                    }
                }

                action.diagnostics.isNotEmpty() -> {
                    { Text(action.diagnostics.first(), maxLines = 1) }
                }

                else -> null
            },
            leadingContent = {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (!enabled) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    } else if (action.isPreferred) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )
    }
}
