package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.ui.WorkspaceEditPreview

@Composable
internal fun WorkspaceEditPreviewDialog(
    preview: WorkspaceEditPreview,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            TinaDialogTitleText(stringResource(Strings.workspace_edit_preview_title))
        },
        text = {
            TinaDialogContentColumn {
                TinaDialogMessageCard(
                    message = stringResource(
                        Strings.workspace_edit_preview_summary,
                        preview.files.size,
                        preview.totalEdits,
                    ),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.14f),
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(Strings.workspace_edit_preview_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    itemsIndexed(
                        items = preview.files,
                        key = { _, file -> file.relativePath },
                    ) { index, file ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = file.relativePath,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = stringResource(
                                        Strings.workspace_edit_preview_file_edits,
                                        file.editCount,
                                    ),
                                )
                            },
                        )
                        if (index < preview.files.lastIndex) {
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            TinaPrimaryButton(
                text = stringResource(Strings.btn_apply),
                onClick = onConfirm,
            )
        },
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_cancel),
                onClick = onDismiss,
            )
        },
    )
}
