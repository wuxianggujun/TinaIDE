package com.wuxianggujun.tinaide.ui.compose.components.markdown

import java.net.URI
import java.util.Locale

internal object MarkdownUrlPolicy {
    private const val MAX_URL_CHARS = 4_096
    private const val MAX_URL_UTF8_BYTES = 8_192

    fun safeLinkUrlOrNull(rawUrl: String): String? =
        parseWebUrl(rawUrl, allowHttp = true, rejectLocalDestination = false)

    fun safeImageUrlOrNull(rawUrl: String): String? =
        parseWebUrl(rawUrl, allowHttp = false, rejectLocalDestination = true)

    private fun parseWebUrl(
        rawUrl: String,
        allowHttp: Boolean,
        rejectLocalDestination: Boolean,
    ): String? {
        val candidate = rawUrl.trim()
        if (candidate.isEmpty() || candidate.length > MAX_URL_CHARS) return null
        if (candidate.any(Char::isISOControl)) return null
        if (candidate.toByteArray(Charsets.UTF_8).size > MAX_URL_UTF8_BYTES) return null

        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        if (!uri.isAbsolute || uri.isOpaque || uri.rawUserInfo != null) return null

        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "https" && (!allowHttp || scheme != "http")) return null
        if (uri.port !in -1..65_535) return null

        val host = uri.host
            ?.removePrefix("[")
            ?.removeSuffix("]")
            ?.trimEnd('.')
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        if (rejectLocalDestination && isLocalOrLiteralHost(host)) return null

        return uri.toASCIIString()
    }

    private fun isLocalOrLiteralHost(host: String): Boolean {
        if (host in LOCAL_HOST_NAMES) return true
        if (LOCAL_HOST_SUFFIXES.any(host::endsWith)) return true
        if (':' in host || '%' in host) return true

        val numericParts = host.split('.')
        if (numericParts.all { part -> part.isNotEmpty() && part.all(Char::isDigit) }) return true
        if (host.startsWith("0x") && host.drop(2).all { it.isHexDigit() }) return true
        return host.all(Char::isDigit)
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private val LOCAL_HOST_NAMES = setOf("localhost", "local", "internal", "home.arpa")
    private val LOCAL_HOST_SUFFIXES = listOf(".localhost", ".local", ".internal", ".home.arpa")
}
