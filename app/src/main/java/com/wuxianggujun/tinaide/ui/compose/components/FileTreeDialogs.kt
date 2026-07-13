package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.common.simplifyPath
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.extensions.toastSuccess
import com.wuxianggujun.tinaide.storage.FileDeletionCancellationSignal
import com.wuxianggujun.tinaide.storage.FileDeletionPhase
import com.wuxianggujun.tinaide.storage.FileDeletionProgress
import com.wuxianggujun.tinaide.storage.FileDeletionResult
import java.io.File
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val fileTreeNameRegex = Regex("[a-zA-Z0-9_.-]+")

/**
 * 文件树对话框集合（Compose + Material3 风格）
 *
 * 包含：
 * - 新建文件夹对话框
 * - 重命名对话框
 * - 删除确认对话框
 */

/**
 * 新建文件夹对话框（Compose）
 */
@Composable
fun CreateFolderDialog(
    parentDir: File,
    onDismiss: () -> Unit
) {
    var folderName by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val parentDirDisplay = simplifyPath(parentDir.absolutePath, context)
    val previewItems = remember(folderName) {
        folderName.trim().takeIf { it.isNotEmpty() }?.let(::listOf).orEmpty()
    }

    LaunchedEffect(Unit) {
        delay(100)
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    val errorFolderNameEmpty = stringResource(Strings.error_folder_name_empty)
    val errorFolderNameInvalid = stringResource(Strings.error_folder_name_invalid)
    val errorFolderExists = stringResource(Strings.error_folder_exists)
    val toastFolderCreatedTemplate = stringResource(Strings.toast_folder_created)
    val errorCreateFolder = stringResource(Strings.error_create_folder)
    val placeholder = stringResource(Strings.new_folder_name_placeholder)

    fun submit() {
        keyboardController?.hide()
        val trimmedName = folderName.trim()
        val validationError = validateFolderName(
            folderName = trimmedName,
            parentDir = parentDir,
            errorFolderNameEmpty = errorFolderNameEmpty,
            errorFolderNameInvalid = errorFolderNameInvalid,
            errorFolderExists = errorFolderExists
        )
        if (validationError != null) {
            errorMessage = validationError
            return
        }

        val newFolder = File(parentDir, trimmedName)
        val created = runCatching { newFolder.mkdirs() }.getOrDefault(false)
        if (!created) {
            errorMessage = errorCreateFolder
            return
        }

        context.toastSuccess(
            String.format(Locale.getDefault(), toastFolderCreatedTemplate, trimmedName)
        )
        onDismiss()
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.new_folder_title)) },
        confirmButton = {
            TinaPrimaryButton(
                text = stringResource(Strings.btn_create),
                onClick = ::submit
            )
        },
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_cancel),
                onClick = {
                    keyboardController?.hide()
                    onDismiss()
                }
            )
        },
        text = {
            TinaDialogContentColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CreationTargetSection(
                    title = stringResource(Strings.label_target_directory),
                    value = parentDirDisplay
                )

                OutlinedTextField(
                    value = folderName,
                    onValueChange = {
                        folderName = it
                        errorMessage = null
                    },
                    label = { Text(stringResource(Strings.new_folder_name_label)) },
                    placeholder = { Text(placeholder) },
                    singleLine = true,
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { { Text(it) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() })
                )

                if (previewItems.isNotEmpty()) {
                    CreationPreviewSection(items = previewItems)
                }
            }
        }
    )
}

private fun validateFolderName(
    folderName: String,
    parentDir: File,
    errorFolderNameEmpty: String,
    errorFolderNameInvalid: String,
    errorFolderExists: String
): String? = when {
    folderName.isEmpty() -> errorFolderNameEmpty
    !folderName.matches(fileTreeNameRegex) -> errorFolderNameInvalid
    File(parentDir, folderName).exists() -> errorFolderExists
    else -> null
}

/**
 * 重命名对话框（Compose）
 */
@Composable
fun RenameDialog(
    file: File,
    onDismiss: () -> Unit,
    onRename: (File, String) -> Boolean = { target, newName ->
        target.parentFile?.let { parentDir ->
            target.renameTo(File(parentDir, newName))
        } ?: false
    }
) {
    val initialSelection = remember(file) {
        val name = file.name
        val dotIndex = name.lastIndexOf('.')
        if (dotIndex > 0 && !file.isDirectory) {
            TextRange(0, dotIndex)
        } else {
            TextRange(0, name.length)
        }
    }

    var textFieldValue by remember(file) {
        mutableStateOf(TextFieldValue(text = file.name, selection = initialSelection))
    }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val targetDirDisplay = simplifyPath(
        file.parentFile?.absolutePath ?: file.absolutePath,
        context
    )

    LaunchedEffect(Unit) {
        delay(100)
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    val errorNameEmpty = stringResource(Strings.error_name_empty)
    val errorFolderNameInvalid = stringResource(Strings.error_folder_name_invalid)
    val errorNameSameExists = stringResource(Strings.error_name_same_exists)
    val toastRenamedTemplate = stringResource(Strings.toast_renamed)
    val renameFailed = stringResource(Strings.rename_failed)

    fun submit() {
        keyboardController?.hide()
        val newName = textFieldValue.text.trim()
        if (newName == file.name) {
            onDismiss()
            return
        }

        val validationError = validateRenameName(
            newName = newName,
            currentName = file.name,
            parentDir = file.parentFile,
            errorNameEmpty = errorNameEmpty,
            errorFolderNameInvalid = errorFolderNameInvalid,
            errorNameSameExists = errorNameSameExists
        )
        if (validationError != null) {
            errorMessage = validationError
            return
        }

        val renamed = runCatching { onRename(file, newName) }.getOrDefault(false)
        if (!renamed) {
            errorMessage = renameFailed
            return
        }

        context.toastSuccess(
            String.format(Locale.getDefault(), toastRenamedTemplate, newName)
        )
        onDismiss()
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.rename_title)) },
        confirmButton = {
            TinaPrimaryButton(
                text = stringResource(Strings.btn_confirm),
                onClick = ::submit
            )
        },
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_cancel),
                onClick = {
                    keyboardController?.hide()
                    onDismiss()
                }
            )
        },
        text = {
            TinaDialogContentColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CreationTargetSection(
                    title = stringResource(Strings.label_target_directory),
                    value = targetDirDisplay
                )

                OutlinedTextField(
                    value = textFieldValue,
                    onValueChange = {
                        textFieldValue = it
                        errorMessage = null
                    },
                    label = { Text(stringResource(Strings.rename_new_name)) },
                    singleLine = true,
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { { Text(it) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() })
                )
            }
        }
    )
}

private fun validateRenameName(
    newName: String,
    currentName: String,
    parentDir: File?,
    errorNameEmpty: String,
    errorFolderNameInvalid: String,
    errorNameSameExists: String
): String? = when {
    newName.isEmpty() -> errorNameEmpty
    newName == currentName -> null
    !newName.matches(fileTreeNameRegex) -> errorFolderNameInvalid
    parentDir != null && File(parentDir, newName).exists() -> errorNameSameExists
    else -> null
}

/**
 * 删除确认对话框（Compose）
 */
@Composable
fun DeleteConfirmDialog(
    file: File,
    onDismiss: () -> Unit,
    onDelete: suspend (
        File,
        FileDeletionCancellationSignal,
        suspend (FileDeletionProgress) -> Unit,
    ) -> FileDeletionResult,
) {
    var errorMessage by remember(file) { mutableStateOf<String?>(null) }
    var isDeleting by remember(file) { mutableStateOf(false) }
    var cancelRequested by remember(file) { mutableStateOf(false) }
    var deletionProgress by remember(file) { mutableStateOf<FileDeletionProgress?>(null) }
    var cancellationSignal by remember(file) { mutableStateOf<FileDeletionCancellationSignal?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val isDirectory = remember(file) { file.isDirectory }
    val typeName = if (isDirectory) {
        stringResource(Strings.delete_folder_title)
    } else {
        stringResource(Strings.delete_file_title)
    }
    val warningMessage = if (isDirectory) {
        stringResource(Strings.delete_folder_confirm, file.name)
    } else {
        stringResource(Strings.delete_file_confirm, file.name)
    }

    val context = LocalContext.current
    val toastDeletedTemplate = stringResource(Strings.toast_deleted)
    val errorDelete = stringResource(Strings.error_delete)
    val deleteCancelledTemplate = stringResource(Strings.delete_cancelled_summary)
    val deleteCancelledRestoreFailed = stringResource(Strings.delete_cancelled_restore_failed)
    val deleteFailedPartialTemplate = stringResource(Strings.delete_failed_partial_summary)
    val deleteFailedRestoreFailed = stringResource(Strings.delete_failed_restore_failed)
    val targetPathDisplay = simplifyPath(file.absolutePath, context)

    fun requestCancellation() {
        if (!isDeleting || cancelRequested) return
        cancelRequested = true
        cancellationSignal?.cancel()
    }

    fun submit() {
        if (isDeleting) return
        isDeleting = true
        cancelRequested = false
        errorMessage = null
        deletionProgress = null
        val signal = FileDeletionCancellationSignal()
        cancellationSignal = signal

        coroutineScope.launch {
            when (val result = onDelete(file, signal) { progress -> deletionProgress = progress }) {
                is FileDeletionResult.Success -> {
                    context.toastSuccess(
                        String.format(Locale.getDefault(), toastDeletedTemplate, file.name)
                    )
                    onDismiss()
                }
                is FileDeletionResult.Cancelled -> {
                    isDeleting = false
                    cancelRequested = false
                    cancellationSignal = null
                    deletionProgress = null
                    errorMessage = if (result.remainingAtOriginalPath) {
                        String.format(
                            Locale.getDefault(),
                            deleteCancelledTemplate,
                            result.deletedItems,
                        )
                    } else {
                        deleteCancelledRestoreFailed
                    }
                }
                is FileDeletionResult.Failure -> {
                    isDeleting = false
                    cancelRequested = false
                    cancellationSignal = null
                    deletionProgress = null
                    errorMessage = when {
                        !result.remainingAtOriginalPath -> deleteFailedRestoreFailed
                        result.deletedItems > 0L -> {
                            String.format(
                                Locale.getDefault(),
                                deleteFailedPartialTemplate,
                                result.deletedItems,
                            )
                        }
                        else -> errorDelete
                    }
                }
            }
        }
    }

    TinaAlertDialog(
        onDismissRequest = { if (isDeleting) requestCancellation() else onDismiss() },
        title = { TinaDialogTitleText(typeName) },
        confirmButton = {
            TinaDangerButton(
                text = stringResource(Strings.btn_delete),
                onClick = ::submit,
                enabled = !isDeleting,
            )
        },
        dismissButton = {
            TinaTextButton(
                text = if (cancelRequested) {
                    stringResource(Strings.delete_cancelling)
                } else {
                    stringResource(Strings.btn_cancel)
                },
                onClick = { if (isDeleting) requestCancellation() else onDismiss() },
                enabled = !cancelRequested,
            )
        },
        text = {
            TinaDialogContentColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TinaDialogMessageCard(
                    message = warningMessage,
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.36f),
                    textColor = MaterialTheme.colorScheme.onErrorContainer
                )

                CreationTargetSection(
                    title = stringResource(Strings.label_target_path),
                    value = targetPathDisplay
                )

                val currentProgress = deletionProgress
                if (currentProgress != null) {
                    DeletionProgressContent(
                        progress = currentProgress,
                        cancelRequested = cancelRequested,
                    )
                } else if (isDeleting) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }

                errorMessage?.let {
                    TinaDialogMessageCard(
                        message = it,
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f),
                        textColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    )
}

@Composable
private fun DeletionProgressContent(
    progress: FileDeletionProgress,
    cancelRequested: Boolean,
) {
    val totalItems = progress.totalItems
    val progressText = when {
        cancelRequested -> stringResource(Strings.delete_cancelling)
        progress.phase == FileDeletionPhase.SCANNING -> {
            stringResource(Strings.delete_scanning_progress, progress.completedItems)
        }
        totalItems != null -> {
            stringResource(
                Strings.delete_items_progress,
                progress.completedItems,
                totalItems,
            )
        }
        else -> stringResource(Strings.delete_in_progress)
    }

    TinaDialogContentColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val fraction = progress.fraction
        if (fraction == null || progress.phase == FileDeletionPhase.SCANNING) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(text = progressText, style = MaterialTheme.typography.bodyMedium)
        progress.currentName?.takeIf(String::isNotBlank)?.let { currentName ->
            Text(
                text = currentName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
