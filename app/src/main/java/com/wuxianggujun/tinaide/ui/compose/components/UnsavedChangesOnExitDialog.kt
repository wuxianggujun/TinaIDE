package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.wuxianggujun.tinaide.core.i18n.Strings

/**
 * 退出时未保存更改确认对话框。
 *
 * 三动作竖排（主 → 危险 → 取消），经 [TinaThreeActionDialog] 统一规范。
 */
@Composable
fun UnsavedChangesOnExitDialog(
    unsavedCount: Int,
    onSaveAllAndExit: () -> Unit,
    onDiscardAndExit: () -> Unit,
    onDismiss: () -> Unit
) {
    TinaThreeActionDialog(
        title = stringResource(Strings.unsaved_changes_on_exit_title),
        message = stringResource(Strings.unsaved_changes_on_exit_message, unsavedCount),
        primaryText = stringResource(Strings.btn_save_all_and_exit),
        secondaryText = stringResource(Strings.btn_discard_and_exit),
        onPrimary = onSaveAllAndExit,
        onSecondary = onDiscardAndExit,
        onDismiss = onDismiss,
        secondaryIsDanger = true,
    )
}
