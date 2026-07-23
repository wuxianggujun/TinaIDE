package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
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
    TinaThreeActionDialog(
        title = stringResource(Strings.unsaved_changes_title),
        message = stringResource(Strings.unsaved_changes_message, fileName),
        primaryText = stringResource(Strings.btn_save_and_close),
        secondaryText = stringResource(Strings.btn_dont_save),
        onPrimary = onSaveAndClose,
        onSecondary = onDiscardAndClose,
        onDismiss = onCancel,
        secondaryIsDanger = true,
    )
}
