package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wuxianggujun.tinaide.core.common.simplifyPath
import com.wuxianggujun.tinaide.core.i18n.Strings
import java.io.File

/**
 * 外部修改冲突对话框：重载 / 保留内存 / 取消。
 */
@Composable
fun ExternalModificationDialog(
    file: File,
    onReload: () -> Unit,
    onKeepMine: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val targetPath = simplifyPath(file.absolutePath, context)
    val message = buildString {
        append(stringResource(Strings.editor_conflict_message, file.name))
        append("\n\n")
        append(stringResource(Strings.label_target_path))
        append('\n')
        append(targetPath)
    }

    TinaThreeActionDialog(
        title = stringResource(Strings.editor_conflict_title),
        message = message,
        primaryText = stringResource(Strings.editor_conflict_reload),
        secondaryText = stringResource(Strings.editor_conflict_overwrite),
        onPrimary = onReload,
        onSecondary = onKeepMine,
        onDismiss = onDismiss,
        dismissText = stringResource(Strings.editor_conflict_cancel),
        secondaryIsDanger = true,
    )
}
