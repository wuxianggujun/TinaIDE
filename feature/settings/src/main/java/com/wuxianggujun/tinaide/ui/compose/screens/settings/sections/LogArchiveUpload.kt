package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.wuxianggujun.tinaide.core.device.DeviceInfoProvider
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.logging.LogArchiveDetector
import com.wuxianggujun.tinaide.core.logging.LogArchiveFormat
import com.wuxianggujun.tinaide.core.logging.LogExportUtils
import com.wuxianggujun.tinaide.core.network.ApiResult
import com.wuxianggujun.tinaide.core.network.aegis.AegisBreadcrumb
import com.wuxianggujun.tinaide.core.network.aegis.AegisHubClient
import com.wuxianggujun.tinaide.core.network.aegis.AegisHubEnvironment
import com.wuxianggujun.tinaide.core.network.aegis.AegisIdentity
import com.wuxianggujun.tinaide.core.network.aegis.AegisIngestReport
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import timber.log.Timber

private const val TAG = "LogArchiveUpload"
private const val COPY_BUFFER_BYTES = 64 * 1024

/** AegisHub 单个附件上限；压缩包本身已压缩，不需要再预留 deflate 余量。 */
internal val LOG_ARCHIVE_MAX_BYTES: Long = LogExportUtils.MAX_UPLOAD_ATTACHMENT_BYTES

/** OpenDocument 的类型过滤；部分文件管理器把压缩包报告为 octet-stream。 */
internal val LOG_ARCHIVE_MIME_TYPES = arrayOf(
    "application/zip",
    "application/x-zip-compressed",
    "application/gzip",
    "application/x-gzip",
    "application/x-tar",
    "application/x-gtar",
    "application/x-compressed-tar",
    "application/octet-stream",
)

internal data class ImportedLogArchive(
    val file: File,
    val displayName: String,
    val format: LogArchiveFormat,
    val sizeBytes: Long,
)

internal sealed interface LogArchiveImport {
    data class Success(val archive: ImportedLogArchive) : LogArchiveImport
    data object Empty : LogArchiveImport
    data class TooLarge(val sizeBytes: Long) : LogArchiveImport
    data object UnsupportedFormat : LogArchiveImport
    data class Failed(val message: String) : LogArchiveImport
}

/**
 * 把用户选择的压缩包复制到应用缓存目录，并按内容魔数校验格式。
 *
 * 复制过程带硬上限，避免 ContentProvider 声明的长度不可信时读入超大文件。
 */
internal suspend fun importLogArchive(
    context: Context,
    uri: Uri,
): LogArchiveImport = withContext(Dispatchers.IO) {
    val metadata = queryArchiveMetadata(context, uri)
    val declaredSize = metadata.sizeBytes
    if (declaredSize != null && declaredSize > LOG_ARCHIVE_MAX_BYTES) {
        return@withContext LogArchiveImport.TooLarge(declaredSize)
    }

    val cacheDir = File(context.cacheDir, "aegis-log-archives").apply { mkdirs() }
    val temp = File.createTempFile("import-", ".part", cacheDir)
    try {
        val copied = context.contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total += read
                    if (total > LOG_ARCHIVE_MAX_BYTES) {
                        return@withContext LogArchiveImport.TooLarge(total)
                    }
                    output.write(buffer, 0, read)
                }
                total
            }
        } ?: return@withContext LogArchiveImport.Failed(
            context.getString(Strings.toast_log_archive_empty)
        )

        if (copied <= 0L) {
            return@withContext LogArchiveImport.Empty
        }

        val format = LogArchiveDetector.detect(temp)
            ?: return@withContext LogArchiveImport.UnsupportedFormat

        val baseName = LogArchiveDetector.safeFileName(metadata.displayName)
        val finalName = LogArchiveDetector.appendExtensionIfMissing(baseName, format)
        val target = File(cacheDir, finalName)
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
        }

        LogArchiveImport.Success(
            ImportedLogArchive(
                file = target,
                displayName = target.name,
                format = format,
                sizeBytes = target.length(),
            )
        )
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "Failed to import log archive")
        LogArchiveImport.Failed(e.message ?: e.javaClass.simpleName)
    } finally {
        if (temp.exists()) temp.delete()
    }
}

/**
 * 提交一条 `diagnostic` 报告，并把这个日志包作为 `application/octet-stream` 附件上传。
 *
 * AegisHub 的附件 MIME 白名单不接受 `application/zip` 等类型，压缩包统一按
 * 不透明字节上传；服务端不会解压或执行附件。
 *
 * @return 成功时返回 AegisHub 报告 ID。
 */
internal suspend fun submitLogBundleToAegisHub(
    context: Context,
    file: File,
    message: String,
): ApiResult<String> {
    val config = AegisHubEnvironment.config
        ?: return ApiResult.Error(-1, context.getString(Strings.aegis_not_configured))

    val deviceInfo = DeviceInfoProvider.getDeviceInfo(context)
    val report = AegisIngestReport(
        eventId = UUID.randomUUID().toString(),
        typeKey = "diagnostic",
        schemaVersion = 1,
        release = config.release,
        buildId = config.buildId,
        environment = config.environment,
        occurredAt = Instant.now().toString(),
        userReference = AegisIdentity.installId(context),
        message = message.take(1024),
        attributes = JsonObject(emptyMap()),
        breadcrumbs = listOf(
            AegisBreadcrumb(
                category = "device",
                level = "info",
                message = "Device context",
                data = buildMap {
                    put("model", deviceInfo.model.take(64))
                    put("os", deviceInfo.osVersion.take(64))
                    put("app_version", deviceInfo.appVersion.take(64))
                },
            ),
            AegisBreadcrumb(
                category = "log",
                level = "info",
                message = "log bundle: ${file.name}".take(1024),
                data = mapOf("size_bytes" to file.length().toString()),
            ),
        ),
    )

    val reportId = when (val submitted = AegisHubClient.submitReport(report)) {
        is ApiResult.Success -> submitted.data.id
        is ApiResult.Error -> return submitted
        is ApiResult.NetworkError -> return submitted
    }

    return when (val attachment = AegisHubClient.uploadAttachment(reportId, file)) {
        is ApiResult.Success -> ApiResult.Success(reportId)
        is ApiResult.Error -> ApiResult.Error(
            attachment.code,
            context.getString(Strings.log_upload_attachment_failed, reportId, attachment.message),
        )
        is ApiResult.NetworkError -> ApiResult.Error(
            -1,
            context.getString(Strings.log_upload_attachment_failed, reportId, attachment.message),
        )
    }
}

private data class ArchiveMetadata(
    val displayName: String?,
    val sizeBytes: Long?,
)

private fun queryArchiveMetadata(context: Context, uri: Uri): ArchiveMetadata {
    return runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use ArchiveMetadata(null, null)
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            ArchiveMetadata(
                displayName = if (nameIndex >= 0) cursor.getString(nameIndex) else null,
                sizeBytes = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    cursor.getLong(sizeIndex)
                } else {
                    null
                },
            )
        } ?: ArchiveMetadata(null, null)
    }.getOrDefault(ArchiveMetadata(null, null))
}
