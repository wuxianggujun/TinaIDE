package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.device.DeviceInfoProvider
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.logging.LogExportProfile
import com.wuxianggujun.tinaide.core.logging.LogExportUtils
import com.wuxianggujun.tinaide.core.network.ApiResult
import com.wuxianggujun.tinaide.core.network.aegis.AegisFeedback
import com.wuxianggujun.tinaide.core.network.aegis.AegisHubEnvironment
import com.wuxianggujun.tinaide.ui.compose.components.TinaCustomDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogActionRow
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogTitleText
import com.wuxianggujun.tinaide.ui.compose.components.TinaPrimaryButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaTextButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaTextField
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 意见反馈对话框。
 *
 * 走 AegisHub 的 `feedback` 报告类型；可选附带一份脱敏运行日志。
 * 未配置 AegisHub 时按钮禁用并提示，不会静默失败。
 */
@Composable
internal fun FeedbackDialog(
    onDismiss: () -> Unit,
    onFinished: (message: String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var message by rememberSaveable { mutableStateOf("") }
    var contact by rememberSaveable { mutableStateOf("") }
    var attachLogs by rememberSaveable { mutableStateOf(true) }
    var isSubmitting by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var submitError by remember { mutableStateOf<String?>(null) }
    // 用户自选的日志压缩包（优先于自动导出的日志），在提交成功/对话框销毁后清理。
    var pickedArchive by remember { mutableStateOf<ImportedLogArchive?>(null) }

    val emptyMessage = stringResource(Strings.feedback_empty_message)
    val archiveTooLargeTemplate = stringResource(Strings.toast_log_archive_too_large)
    val archiveUnsupported = stringResource(Strings.toast_log_archive_unsupported)
    val archiveEmpty = stringResource(Strings.toast_log_archive_empty)
    val reportingConfigured = AegisHubEnvironment.config != null

    val logArchivePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            when (val imported = importLogArchive(context, uri)) {
                is LogArchiveImport.Success -> {
                    pickedArchive?.file?.let { previous -> runCatching { previous.delete() } }
                    pickedArchive = imported.archive
                    attachLogs = true
                    submitError = null
                }
                LogArchiveImport.UnsupportedFormat ->
                    submitError = archiveUnsupported

                LogArchiveImport.Empty ->
                    submitError = archiveEmpty

                is LogArchiveImport.TooLarge -> {
                    val megabytes = (imported.sizeBytes + 1024 * 1024 - 1) / (1024 * 1024)
                    submitError = String.format(
                        Locale.getDefault(),
                        archiveTooLargeTemplate,
                        megabytes
                    )
                }

                is LogArchiveImport.Failed ->
                    submitError = imported.message
            }
        }
    }

    // 对话框被关闭时也要清掉自选压缩包在缓存里的副本。
    DisposableEffect(pickedArchive) {
        val archive = pickedArchive
        onDispose { archive?.file?.let { file -> runCatching { file.delete() } } }
    }

    TinaCustomDialog(onDismissRequest = { if (!isSubmitting) onDismiss() }) {
        TinaDialogTitleText(stringResource(Strings.feedback_dialog_title))

        Spacer(Modifier.height(12.dp))

        TinaTextField(
            value = message,
            onValueChange = {
                message = it
                errorText = null
            },
            label = stringResource(Strings.feedback_message_label),
            placeholder = stringResource(Strings.feedback_message_placeholder),
            singleLine = false,
            minLines = 4,
            maxLines = 8,
            isError = errorText != null,
            errorText = errorText,
            enabled = !isSubmitting,
        )

        Spacer(Modifier.height(10.dp))

        TinaTextField(
            value = contact,
            onValueChange = { contact = it.take(128) },
            label = stringResource(Strings.feedback_contact_label),
            placeholder = stringResource(Strings.feedback_contact_placeholder),
            singleLine = true,
            enabled = !isSubmitting,
        )

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Strings.feedback_attach_logs),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(Strings.feedback_attach_logs_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = attachLogs,
                onCheckedChange = { attachLogs = it },
                enabled = !isSubmitting && pickedArchive == null,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Strings.feedback_attach_archive),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = pickedArchive?.let { archive ->
                        context.getString(
                            Strings.feedback_attach_archive_selected,
                            archive.displayName
                        )
                    } ?: stringResource(Strings.feedback_attach_archive_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            Spacer(Modifier.width(12.dp))
            if (pickedArchive == null) {
                TinaTextButton(
                    text = stringResource(Strings.feedback_attach_archive_pick),
                    onClick = { logArchivePicker.launch(LOG_ARCHIVE_MIME_TYPES) },
                    enabled = !isSubmitting,
                )
            } else {
                TinaTextButton(
                    text = stringResource(Strings.feedback_attach_archive_remove),
                    onClick = {
                        pickedArchive?.file?.let { file -> runCatching { file.delete() } }
                        pickedArchive = null
                    },
                    enabled = !isSubmitting,
                )
            }
        }

        (submitError ?: if (!reportingConfigured) stringResource(Strings.aegis_not_configured) else null)?.let { message ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(16.dp))

        TinaDialogActionRow {
            TinaTextButton(
                text = stringResource(Strings.btn_cancel),
                onClick = { if (!isSubmitting) onDismiss() },
            )
            TinaPrimaryButton(
                text = stringResource(
                    if (isSubmitting) Strings.feedback_submitting else Strings.feedback_submit
                ),
                onClick = {
                    if (isSubmitting) return@TinaPrimaryButton
                    val trimmed = AegisFeedback.normalizeMessage(message)
                    if (trimmed.isEmpty()) {
                        errorText = emptyMessage
                        return@TinaPrimaryButton
                    }
                    isSubmitting = true
                    errorText = null
                    submitError = null
                    scope.launch {
                        val result = try {
                            submitFeedback(
                                context = context,
                                message = trimmed,
                                contact = contact,
                                attachLogs = attachLogs,
                                pickedArchive = pickedArchive,
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Timber.tag("FeedbackDialog").w(e, "Failed to prepare feedback")
                            ApiResult.Error(-1, context.getString(Strings.aegis_report_failed))
                        } finally {
                            isSubmitting = false
                        }
                        when (result) {
                            is ApiResult.Success -> {
                                pickedArchive?.file?.let { file -> runCatching { file.delete() } }
                                pickedArchive = null
                                val text = result.data.attachmentError?.let { reason ->
                                    context.getString(Strings.feedback_submitted_with_log_warning, reason)
                                } ?: context.getString(Strings.feedback_submitted)
                                onFinished(text)
                            }
                            is ApiResult.Error -> {
                                submitError = context.getString(Strings.feedback_failed, result.message)
                            }
                            is ApiResult.NetworkError -> {
                                submitError = context.getString(Strings.feedback_failed, result.message)
                            }
                        }
                    }
                },
                enabled = !isSubmitting && reportingConfigured,
            )
        }
    }
}

private suspend fun submitFeedback(
    context: Context,
    message: String,
    contact: String,
    attachLogs: Boolean,
    pickedArchive: ImportedLogArchive?,
): ApiResult<AegisFeedback.Result> = withContext(Dispatchers.IO) {
    var attachment: File? = null
    var generated = false
    try {
        // 用户自选的压缩包优先；只有没选时才回退到自动导出的脱敏日志包。
        if (pickedArchive != null) {
            attachment = pickedArchive.file
        } else if (attachLogs) {
            attachment = LogExportUtils.exportLogs(
                context = context,
                profile = LogExportProfile.SERVER_UPLOAD,
                maxInputBytes = LogExportUtils.UPLOAD_MAX_INPUT_BYTES,
            )
            generated = true
        }
        AegisFeedback.submit(
            context = context,
            draft = AegisFeedback.Draft(
                message = message,
                contact = contact,
                appVersion = DeviceInfoProvider.versionName,
                device = "${DeviceInfoProvider.getDeviceModel()} / ${DeviceInfoProvider.getOsVersion()}",
                attachment = attachment,
            ),
        )
    } finally {
        // 自选压缩包的缓存副本由调用方负责清理，这里只删除本次自动导出的临时包。
        if (generated) attachment?.let { file -> runCatching { file.delete() } }
    }
}
