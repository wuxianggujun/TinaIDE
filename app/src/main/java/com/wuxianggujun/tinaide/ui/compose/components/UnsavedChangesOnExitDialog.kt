package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.i18n.Strings

/**
 * 退出时未保存更改确认对话框。
 *
 * 三动作竖排（主 → 危险 → 取消），避免小屏横排三钮挤成一团。
 */
@Composable
fun UnsavedChangesOnExitDialog(
    unsavedCount: Int,
    onSaveAllAndExit: () -> Unit,
    onDiscardAndExit: () -> Unit,
    onDismiss: () -> Unit
) {
    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.unsaved_changes_on_exit_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogMessageCard(
                    message = stringResource(Strings.unsaved_changes_on_exit_message, unsavedCount)
                )
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TinaPrimaryButton(
                    text = stringResource(Strings.btn_save_all_and_exit),
                    onClick = onSaveAllAndExit,
                    modifier = Modifier.fillMaxWidth(),
                )
                TinaDangerOutlinedButton(
                    text = stringResource(Strings.btn_discard_and_exit),
                    onClick = onDiscardAndExit,
                    modifier = Modifier.fillMaxWidth(),
                )
                TinaTextButton(
                    text = stringResource(Strings.btn_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}
