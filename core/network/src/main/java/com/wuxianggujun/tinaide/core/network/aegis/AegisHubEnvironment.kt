package com.wuxianggujun.tinaide.core.network.aegis

import java.util.Locale
import java.util.UUID

/**
 * 内嵌 AegisHub 上报配置。
 *
 * AegisHub 是唯一的日志/崩溃/反馈上报后端，配置由 `:app` 在 `CoreServiceRegistrar`
 * 中从构建期密钥注入。开源构建若未注入密钥，[config] 为 null，所有上报会以明确的
 * 错误日志跳过，而不是退化到其他后端。
 */
object AegisHubEnvironment {

    data class Config(
        val baseUrl: String,
        val appId: String,
        val ingestKey: String,
        val release: String,
        val buildId: String?,
        val environment: String,
    ) {
        val isConfigured: Boolean
            get() = baseUrl.isNotBlank() &&
                runCatching { UUID.fromString(appId).toString().equals(appId, ignoreCase = true) }.getOrDefault(false) &&
                ingestKey.isNotBlank() &&
                ingestKey.none { it.isISOControl() } &&
                release.isNotBlank()
    }

    @Volatile
    private var current: Config? = null

    /**
     * 由 `:app` 在启动时调用。任一关键字段为空即视为未配置。
     */
    fun initialize(
        baseUrl: String,
        appId: String,
        ingestKey: String,
        release: String,
        buildId: String?,
        environment: String,
    ) {
        current = Config(
            baseUrl = normalizeBaseUrl(baseUrl),
            appId = appId.trim(),
            ingestKey = ingestKey.trim(),
            release = release.trim(),
            buildId = buildId?.trim()?.takeIf { it.isNotEmpty() },
            environment = environment.trim().ifEmpty { "production" },
        )
    }

    /** 仅供单元测试使用。 */
    internal fun replaceForTesting(config: Config?) {
        current = config
    }

    /** 已配置且字段完整时返回配置，否则返回 null。 */
    val config: Config?
        get() = current?.takeIf { it.isConfigured }

    private fun normalizeBaseUrl(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return ""
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return ""
        val allowed = scheme == "https" ||
            (scheme == "http" && (uri.host == "localhost" || uri.host == "127.0.0.1"))
        if (!allowed || uri.host.isNullOrBlank() || uri.userInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null ||
            (uri.port != -1 && uri.port !in 1..65535)
        ) return ""
        return trimmed
    }
}
