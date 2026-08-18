package com.wuxianggujun.tinaide.core.lsp

import java.net.URI
import java.util.Locale

/** Returns a stable key for LSP document URIs without changing non-file schemes. */
fun canonicalizeLspDocumentUri(uri: String): String {
    val parsed = runCatching { URI(uri).normalize() }.getOrNull() ?: return uri
    if (!parsed.scheme.equals("file", ignoreCase = true)) return uri

    val rawPath = parsed.rawPath ?: return uri
    val explicitAuthority = parsed.rawAuthority
    val uncPath = if (explicitAuthority.isNullOrEmpty()) splitUncPath(rawPath) else null
    val authority = (explicitAuthority ?: uncPath?.first)?.lowercase(Locale.ROOT)
    val canonicalPath = normalizeWindowsDriveLetter(uncPath?.second ?: rawPath)
    val base = if (authority.isNullOrEmpty()) {
        "file:///" + canonicalPath.trimStart('/')
    } else {
        "file://$authority${if (canonicalPath.startsWith('/')) canonicalPath else "/$canonicalPath"}"
    }
    val query = parsed.rawQuery?.let { "?$it" }.orEmpty()
    val fragment = parsed.rawFragment?.let { "#$it" }.orEmpty()
    return base + query + fragment
}

private fun splitUncPath(rawPath: String): Pair<String, String>? {
    if (!rawPath.startsWith("//")) return null
    val withoutLeadingSlashes = rawPath.trimStart('/')
    if (withoutLeadingSlashes.isEmpty()) return null
    val separatorIndex = withoutLeadingSlashes.indexOf('/')
    val authority = if (separatorIndex >= 0) {
        withoutLeadingSlashes.substring(0, separatorIndex)
    } else {
        withoutLeadingSlashes
    }
    if (authority.isEmpty()) return null
    val path = if (separatorIndex >= 0) {
        withoutLeadingSlashes.substring(separatorIndex)
    } else {
        "/"
    }
    return authority to path
}

private fun normalizeWindowsDriveLetter(path: String): String {
    val driveIndex = if (path.startsWith('/')) 1 else 0
    if (path.length <= driveIndex + 1 || path[driveIndex + 1] != ':' || !path[driveIndex].isLetter()) {
        return path
    }
    return path.replaceRange(
        driveIndex,
        driveIndex + 1,
        path[driveIndex].uppercaseChar().toString(),
    )
}

internal fun lspDocumentUrisEquivalent(left: String, right: String): Boolean =
    canonicalizeLspDocumentUri(left) == canonicalizeLspDocumentUri(right)
