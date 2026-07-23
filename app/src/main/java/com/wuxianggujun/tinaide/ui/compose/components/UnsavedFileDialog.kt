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
 * 关闭未保存文件的确认对话框。
 *
 * 三动作竖排：保存并关闭 → 不保存关闭 → 取消。
 */
@Composable
fun UnsavedFileDialog(
    fileName: String,
    onSaveAndClose: () -> Unit,
    onDiscardAndClose: () -> Unit,
    onCancel: () -> Unit
) {
    TinaAlertDialog(
        onDismissRequest = onCancel,
        title = { TinaDialogTitleText(stringResource(Strings.unsaved_changes_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogMessageCard(
                    message = stringResource(Strings.unsaved_changes_message, fileName)
                )
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TinaPrimaryButton(
                    text = stringResource(Strings.btn_save_and_close),
                    onClick = onSaveAndClose,
                    modifier = Modifier.fillMaxWidth(),
                )
                TinaDangerOutlinedButton(
                    text = stringResource(Strings.btn_dont_save),
                    onClick = onDiscardAndClose,
                    modifier = Modifier.fillMaxWidth(),
                )
                TinaTextButton(
                    text = stringResource(Strings.btn_cancel),
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}
