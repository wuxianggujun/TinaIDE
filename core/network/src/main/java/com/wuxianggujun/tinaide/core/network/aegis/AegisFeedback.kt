package com.wuxianggujun.tinaide.core.network.aegis

import android.content.Context
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.network.ApiResult
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * AegisHub `feedback` 报告（schema v1）。
 *
 * `attributes` 的键必须与 AegisHub 控制台里 `feedback` 报告类型的字段声明一致：
 * `contact`、`app_version`、`device`、`source`。
 */
object AegisFeedback {

    data class Draft(
        val message: String,
        val contact: String?,
        val appVersion: String,
        val device: String?,
        val source: String = "android-settings",
        val attachment: File? = null,
        val attachmentDisplayName: String? = null,
    )

    data class Result(
        val reportId: String,
        val attachmentError: String? = null,
    )

    suspend fun submit(context: Context, draft: Draft): ApiResult<Result> {
        val config = AegisHubEnvironment.config
            ?: return ApiResult.Error(-1, context.getString(Strings.aegis_not_configured))
        val message = draft.message.trim()
        if (message.isEmpty()) return ApiResult.Error(-1, context.getString(Strings.feedback_empty_message))

        // AegisHub 按 UTF-8 字节数校验所有文本字段，中文场景下按字符截断会超限。
        val attributes = buildMap<String, JsonPrimitive> {
            draft.contact?.trim()?.takeIf { it.isNotEmpty() }?.let {
                put("contact", JsonPrimitive(truncateUtf8Bytes(it, 256)))
            }
            draft.appVersion.trim().takeIf { it.isNotEmpty() }?.let {
                put("app_version", JsonPrimitive(truncateUtf8Bytes(it, 64)))
            }
            draft.device?.trim()?.takeIf { it.isNotEmpty() }?.let {
                put("device", JsonPrimitive(truncateUtf8Bytes(it, 128)))
            }
            draft.source.trim().takeIf { it.isNotEmpty() }?.let {
                put("source", JsonPrimitive(truncateUtf8Bytes(it, 32)))
            }
        }

        val report = AegisIngestReport(
            eventId = UUID.randomUUID().toString(),
            typeKey = "feedback",
            schemaVersion = 1,
            release = config.release,
            buildId = config.buildId,
            environment = config.environment,
            occurredAt = Instant.now().toString(),
            userReference = AegisIdentity.installId(context),
            message = truncateUtf8Bytes(message, 8192),
            attributes = JsonObject(attributes),
            exception = null,
            breadcrumbs = emptyList(),
        )

        val receipt = when (val submitted = AegisHubClient.submitReport(report)) {
            is ApiResult.Success -> submitted.data
            is ApiResult.Error -> return submitted
            is ApiResult.NetworkError -> return submitted
        }

        val attachment = draft.attachment
            ?: return ApiResult.Success(Result(receipt.id))

        return when (val uploaded = AegisHubClient.uploadAttachment(
            receipt.id, attachment, displayName = draft.attachmentDisplayName ?: attachment.name,
        )) {
            is ApiResult.Success -> ApiResult.Success(Result(receipt.id))
            is ApiResult.Error -> ApiResult.Success(Result(receipt.id, uploaded.message))
            is ApiResult.NetworkError -> ApiResult.Success(Result(receipt.id, uploaded.message))
        }
    }

    /**
     * AegisHub 要求 `feedback` 报告至少带一条消息，且 schema 由后台定义；
     * 这里只做客户端侧的长度收敛，避免超限被拒绝。
     */
    fun normalizeMessage(value: String): String = truncateUtf8Bytes(value.trim(), 8192)

    /** 按 UTF-8 字节数截断，不切坏多字节字符。 */
    internal fun truncateUtf8Bytes(value: String, maxBytes: Int): String {
        if (value.isEmpty() || maxBytes <= 0) return ""
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return value
        var end = maxBytes
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) {
            end--
        }
        return String(bytes, 0, end, Charsets.UTF_8)
    }
}
