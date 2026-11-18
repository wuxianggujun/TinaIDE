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
import androidx.compose.runtime.LaunchedEffect
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
 * 走 AegisHub 的 `feedback` 报告类型；日志与设备信息默认不上传，需用户主动选择。
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
    var diagnostics by rememberSaveable(stateSaver = FeedbackDiagnostics.Saver) {
        mutableStateOf<FeedbackDiagnostics>(FeedbackDiagnostics.None)
    }
    var isSubmitting by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var submitError by remember { mutableStateOf<String?>(null) }
    val selected = diagnostics as? FeedbackDiagnostics.Selected
    var importing by remember { mutableStateOf(false) }
    fun clearSelection() {
        (diagnostics as? FeedbackDiagnostics.Selected)?.remove(importedArchiveCache(context))
        diagnostics = FeedbackDiagnostics.None
        submitError = null
    }
    fun dismiss() {
        if (!isSubmitting && !importing) {
            clearSelection()
            onDismiss()
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            removeExpiredLogArchives(importedArchiveCache(context), selected?.cacheKey)
        }
    }

    val emptyMessage = stringResource(Strings.feedback_empty_message)
    val archiveTooLargeTemplate = stringResource(Strings.toast_log_archive_too_large)
    val archiveUnsupported = stringResource(Strings.toast_log_archive_unsupported)
    val archiveEmpty = stringResource(Strings.toast_log_archive_empty)
    val reportingConfigured = AegisHubEnvironment.config != null

    val logArchivePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (isSubmitting || importing) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            try {
                when (val imported = importLogArchive(context, uri)) {
                    is LogArchiveImport.Success -> {
                        clearSelection()
                        diagnostics = FeedbackDiagnostics.Selected(imported.archive.file.name, imported.archive.displayName)
                        submitError = null
                    }
                    LogArchiveImport.UnsupportedFormat -> submitError = archiveUnsupported
                    LogArchiveImport.Empty -> submitError = archiveEmpty
                    is LogArchiveImport.TooLarge -> {
                        val megabytes = (imported.sizeBytes + 1024 * 1024 - 1) / (1024 * 1024)
                        submitError = String.format(Locale.getDefault(), archiveTooLargeTemplate, megabytes)
                    }
                    is LogArchiveImport.Failed -> submitError = imported.message
                }
            } finally {
                importing = false
            }
        }
    }

    TinaCustomDialog(onDismissRequest = ::dismiss) {
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
                checked = diagnostics == FeedbackDiagnostics.Automatic,
                onCheckedChange = { diagnostics = if (it) FeedbackDiagnostics.Automatic else FeedbackDiagnostics.None },
                enabled = !isSubmitting && !importing && selected == null,
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
                    text = selected?.let { archive ->
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
            if (selected == null) {
                TinaTextButton(
                    text = stringResource(Strings.feedback_attach_archive_pick),
                    onClick = { logArchivePicker.launch(LOG_ARCHIVE_MIME_TYPES) },
                    enabled = !isSubmitting && !importing,
                )
            } else {
                TinaTextButton(
                    text = stringResource(Strings.feedback_attach_archive_remove),
                    onClick = ::clearSelection,
                    enabled = !isSubmitting && !importing,
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
                onClick = ::dismiss,
                enabled = !isSubmitting && !importing,
            )
            TinaPrimaryButton(
                text = stringResource(
                    if (isSubmitting) Strings.feedback_submitting else Strings.feedback_submit
                ),
                onClick = {
                    if (isSubmitting || importing) return@TinaPrimaryButton
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
                                diagnostics = diagnostics,
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
                                clearSelection()
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
                enabled = !isSubmitting && !importing && reportingConfigured,
            )
        }
    }
}

internal suspend fun submitFeedback(
    context: Context,
    message: String,
    contact: String,
    diagnostics: FeedbackDiagnostics,
    send: suspend (Context, AegisFeedback.Draft) -> ApiResult<AegisFeedback.Result> = AegisFeedback::submit,
): ApiResult<AegisFeedback.Result> = withContext(Dispatchers.IO) {
    var attachment: File? = null
    var generated = false
    try {
        if (diagnostics is FeedbackDiagnostics.Selected) {
            attachment = diagnostics.resolve(importedArchiveCache(context))
                ?: return@withContext ApiResult.Error(-1, context.getString(Strings.feedback_archive_reselect))
        } else if (diagnostics == FeedbackDiagnostics.Automatic) {
            attachment = LogExportUtils.exportLogs(
                context = context,
                profile = LogExportProfile.SERVER_UPLOAD,
                maxInputBytes = LogExportUtils.UPLOAD_MAX_INPUT_BYTES,
            )
            generated = true
        }
        send(
            context,
            AegisFeedback.Draft(
                message = message,
                contact = contact,
                appVersion = DeviceInfoProvider.versionName,
                device = if (diagnostics == FeedbackDiagnostics.Automatic) {
                    "${DeviceInfoProvider.getDeviceModel()} / ${DeviceInfoProvider.getOsVersion()}"
                } else null,
                attachment = attachment,
                attachmentDisplayName = (diagnostics as? FeedbackDiagnostics.Selected)?.displayName,
            ),
        )
    } finally {
        // 自选压缩包的缓存副本由调用方负责清理，这里只删除本次自动导出的临时包。
        if (generated) attachment?.let(::deleteTemporaryArchive)
    }
}
