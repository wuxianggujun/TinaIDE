package com.wuxianggujun.tinaide.core.network.aegis

import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str
import com.wuxianggujun.tinaide.core.network.ApiResult
import com.wuxianggujun.tinaide.core.network.OkHttpClientProvider
import com.wuxianggujun.tinaide.core.network.executeCancellable
import com.wuxianggujun.tinaide.core.network.readUtf8Limited
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import kotlin.coroutines.coroutineContext

/**
 * AegisHub ingest 客户端。
 *
 * 与服务端契约保持一致：
 * - `POST /ingest/v1/apps/{app_id}/reports`，JSON，返回 `{id, status}`；
 * - `POST /ingest/v1/apps/{app_id}/reports/{report_id}/attachments`，
 *   multipart 字段名固定为 `file`；
 * - 两个接口都使用 `Authorization: Bearer <ingest key>`。
 */
object AegisHubClient {
    private const val TAG = "AegisHubClient"
    private const val JSON_MEDIA_TYPE = "application/json"
    private const val MAX_RESPONSE_BYTES = 1024 * 1024
    internal const val MAX_ATTACHMENT_BYTES = 10L * 1024 * 1024

    /** Network/5xx/429 failures are retried with a short backoff, then surfaced. */
    private const val MAX_ATTEMPTS = 3
    private val retryDelaysMs = longArrayOf(400L, 1_200L)

    private val json = JsonSerializer.default
    private val client: OkHttpClient by lazy {
        OkHttpClientProvider.default.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            // Bounds a single call end-to-end so a stalled socket cannot block the
            // crash uploader forever; retries are applied on top of this timeout.
            .callTimeout(240, TimeUnit.SECONDS)
            .build()
    }

    suspend fun submitReport(report: AegisIngestReport): ApiResult<AegisReportReceipt> =
        withContext(Dispatchers.IO) {
            val config = AegisHubEnvironment.config
                ?: return@withContext ApiResult.Error(-1, Strings.aegis_not_configured.str())
            val body = runCatching { json.encodeToString(AegisIngestReport.serializer(), report) }
                .getOrElse {
                    Timber.tag(TAG).e(it, "Failed to encode AegisHub report")
                    return@withContext ApiResult.Error(-1, Strings.aegis_report_encode_failed.str())
                }
            val request = Request.Builder()
                .url("${config.baseUrl}/ingest/v1/apps/${config.appId}/reports")
                .header("Authorization", "Bearer ${config.ingestKey}")
                .header("User-Agent", "TinaIDE/${config.release}")
                .post(body.toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
                .build()

            execute(request) { responseBody ->
                json.decodeFromString(AegisReportReceipt.serializer(), responseBody)
            }
        }

    suspend fun uploadAttachment(
        reportId: String,
        file: File,
        contentType: String = "application/octet-stream",
        displayName: String = file.name,
    ): ApiResult<AegisAttachmentReceipt> = withContext(Dispatchers.IO) {
        val config = AegisHubEnvironment.config
            ?: return@withContext ApiResult.Error(-1, Strings.aegis_not_configured.str())
        if (!file.isFile || file.length() <= 0L) {
            return@withContext ApiResult.Error(-1, Strings.aegis_attachment_empty.str())
        }
        if (file.length() > MAX_ATTACHMENT_BYTES) {
            return@withContext ApiResult.Error(-1, Strings.aegis_attachment_too_large.str())
        }
        if (runCatching { UUID.fromString(reportId) }.isFailure) {
            return@withContext ApiResult.Error(-1, Strings.aegis_invalid_report_id.str())
        }
        val safeName = sanitizeAttachmentName(displayName)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", safeName, file.asRequestBody(contentType.toMediaType()))
            .build()
        val request = Request.Builder()
            .url("${config.baseUrl}/ingest/v1/apps/${config.appId}/reports/$reportId/attachments")
            .header("Authorization", "Bearer ${config.ingestKey}")
            .header("User-Agent", "TinaIDE/${config.release}")
            .post(body)
            .build()

        execute(request) { responseBody ->
            json.decodeFromString(AegisAttachmentReceipt.serializer(), responseBody)
        }
    }

    /**
     * 先提交报告，再上传一个附件。
     *
     * 报告被服务端接受后，即使附件失败也只返回附件错误，避免重复上报同一事件。
     */
    suspend fun submitReportWithAttachment(
        report: AegisIngestReport,
        file: File,
        contentType: String = "application/octet-stream",
    ): AegisSubmission {
        return when (val submitted = submitReport(report)) {
            is ApiResult.NetworkError -> AegisSubmission.ReportFailed(submitted)
            is ApiResult.Error -> AegisSubmission.ReportFailed(submitted)
            is ApiResult.Success -> {
                val attachment = uploadAttachment(submitted.data.id, file, contentType)
                AegisSubmission.Submitted(submitted.data, attachment)
            }
        }
    }

    private fun sanitizeAttachmentName(name: String): String {
        val cleaned = name
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t\\u0000]"), "_")
            .trim()
            .trimStart('.')
        if (cleaned.isEmpty()) return "attachment.bin"

        // AegisHub 的文件名上限是 200 字节（不是字符），中文/emoji 文件名必须按字节裁剪。
        val dot = cleaned.lastIndexOf('.')
        val stem = if (dot > 0) cleaned.substring(0, dot) else cleaned
        val extension = if (dot > 0) cleaned.substring(dot + 1).take(16) else ""
        val extensionBytes = extension.toByteArray(Charsets.UTF_8).size
        val maxStemBytes = (196 - extensionBytes - if (extension.isEmpty()) 0 else 1)
            .coerceAtLeast(1)
        val safeStem = AegisFeedback.truncateUtf8Bytes(stem, maxStemBytes)
            .ifBlank { "attachment" }
        return if (extension.isEmpty()) safeStem else "$safeStem.$extension"
    }

    private suspend inline fun <T> execute(
        request: Request,
        decode: (String) -> T,
    ): ApiResult<T> {
        var attempt = 0
        while (true) {
            val result = executeOnce(request, decode)
            val retryable = when (result) {
                is ApiResult.NetworkError -> true
                is ApiResult.Error -> result.code == 429 || result.code >= 500
                is ApiResult.Success -> false
            }
            if (!retryable || attempt >= MAX_ATTEMPTS - 1) return result
            val delayMs = retryDelaysMs.getOrElse(attempt) { retryDelaysMs.last() }
            Timber.tag(TAG).w(
                "AegisHub request retry %d/%d in %d ms",
                attempt + 1,
                MAX_ATTEMPTS,
                delayMs,
            )
            delay(delayMs)
            attempt++
        }
    }

    private suspend inline fun <T> executeOnce(
        request: Request,
        decode: (String) -> T,
    ): ApiResult<T> {
        return try {
            client.newCall(request).executeCancellable().use { response ->
                val responseBody = response.body?.readUtf8Limited(MAX_RESPONSE_BYTES).orEmpty()
                if (!response.isSuccessful) {
                    val message = parseErrorMessage(responseBody)
                        ?: Strings.error_request_failed_with_code.str(response.code)
                    Timber.tag(TAG).e("AegisHub HTTP %d: %s", response.code, message)
                    return ApiResult.Error(response.code, message)
                }
                val decoded = try {
                    decode(responseBody)
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to parse AegisHub response")
                    return ApiResult.Error(-1, Strings.error_response_parse_failed.str())
                }
                ApiResult.Success(decoded)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            coroutineContext.ensureActive()
            Timber.tag(TAG).e(e, "AegisHub network error")
            ApiResult.NetworkError(Strings.error_network_connection_failed.str())
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "AegisHub request failed")
            ApiResult.Error(-1, Strings.aegis_report_failed.str())
        }
    }

    private fun parseErrorMessage(body: String): String? {
        if (body.isBlank()) return null
        return runCatching {
            val root = json.parseToJsonElement(body).let { it as JsonObject }
            val error = root["error"] as? JsonObject ?: return@runCatching null
            error["message"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}

sealed class AegisSubmission {
    data class Submitted(
        val report: AegisReportReceipt,
        val attachment: ApiResult<AegisAttachmentReceipt>,
    ) : AegisSubmission()

    data class ReportFailed(val error: ApiResult<AegisReportReceipt>) : AegisSubmission()
}

@Serializable
data class AegisIngestReport(
    @SerialName("event_id") val eventId: String,
    @SerialName("type_key") val typeKey: String,
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val release: String,
    @SerialName("build_id") val buildId: String? = null,
    val environment: String,
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("user_reference") val userReference: String? = null,
    val message: String,
    val attributes: JsonObject = JsonObject(emptyMap()),
    val exception: AegisException? = null,
    val breadcrumbs: List<AegisBreadcrumb> = emptyList(),
)

@Serializable
data class AegisException(
    val type: String,
    val message: String,
    val frames: List<AegisFrame>,
)

@Serializable
data class AegisFrame(
    val function: String,
    val file: String,
    val line: Int? = null,
    val module: String? = null,
)

@Serializable
data class AegisBreadcrumb(
    val timestamp: String? = null,
    val category: String? = null,
    val level: String? = null,
    val message: String,
    val data: Map<String, String> = emptyMap(),
)

@Serializable
data class AegisReportReceipt(
    val id: String,
    val status: String = "pending",
)

@Serializable
data class AegisAttachmentReceipt(
    val id: String,
    @SerialName("report_id") val reportId: String,
    @SerialName("file_name") val fileName: String,
    @SerialName("content_type") val contentType: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    val sha256: String,
    @SerialName("created_at") val createdAt: String,
)
