package com.wuxianggujun.tinaide.core.lsp

import java.io.File
import java.net.URI
import java.util.Locale

/** Returns a stable key for LSP document URIs without changing non-file schemes. */
fun canonicalizeLspDocumentUri(uri: String): String {
    val parsed = runCatching { URI(uri).normalize() }.getOrNull() ?: return uri
    if (!parsed.scheme.equals("file", ignoreCase = true)) return uri

    val rawPath = parsed.rawPath ?: return uri
    val explicitAuthority = parsed.rawAuthority
    val uncPath = if (explicitAuthority.isNullOrEmpty()) splitUncPath(rawPath) else null
    val authority = (explicitAuthority ?: uncPath?.first)
        ?.lowercase(Locale.ROOT)
        ?.let(::normalizePercentEncoding)
    val canonicalPath = normalizePercentEncoding(
        normalizeWindowsDriveLetter(uncPath?.second ?: rawPath)
    )
    val base = if (authority.isNullOrEmpty()) {
        "file:///" + canonicalPath.trimStart('/')
    } else {
        "file://$authority${if (canonicalPath.startsWith('/')) canonicalPath else "/$canonicalPath"}"
    }
    val query = parsed.rawQuery?.let { "?$it" }.orEmpty()
    val fragment = parsed.rawFragment?.let { "#$it" }.orEmpty()
    return base + query + fragment
}

/**
 * Builds the document URI to hand to a language server.
 *
 * Prefer this over [File.toURI] for anything that crosses the LSP boundary: `File.toURI()` keeps
 * non-ASCII characters literal, while servers answer with RFC 3986 percent-encoded URIs.
 */
fun File.toLspDocumentUri(): String = canonicalizeLspDocumentUri(toURI().toString())

/** Resolves an LSP document URI back to a local filesystem path. */
fun lspDocumentUriToFilePath(uri: String): String? {
    val parsed = runCatching { URI(uri) }.getOrNull() ?: return null
    if (!parsed.scheme.equals("file", ignoreCase = true)) return null
    // `path` is percent-decoded; `rawPath` would leave %XX sequences in the filename.
    return parsed.path?.takeIf { it.isNotEmpty() }
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

private const val UPPER_HEX_DIGITS = "0123456789ABCDEF"

/**
 * Percent-encodes non-ASCII characters as UTF-8 and uppercases existing escapes.
 *
 * Without this, `file:/project/中文.cpp` (from [File.toURI]) and
 * `file:///project/%E4%B8%AD%E6%96%87.cpp` (from clangd) never compare equal.
 */
private fun normalizePercentEncoding(value: String): String {
    if (value.none { it.code >= 0x80 || it == '%' }) return value

    val builder = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        when {
            char == '%' && index + 2 < value.length &&
                value[index + 1].isAsciiHexDigit() && value[index + 2].isAsciiHexDigit() -> {
                builder.append('%')
                builder.append(value[index + 1].uppercaseChar())
                builder.append(value[index + 2].uppercaseChar())
                index += 3
            }

            char.code < 0x80 -> {
                builder.append(char)
                index += 1
            }

            else -> {
                val codePointEnd = value.offsetByCodePoints(index, 1)
                value.substring(index, codePointEnd)
                    .toByteArray(Charsets.UTF_8)
                    .forEach { byte ->
                        val unsigned = byte.toInt() and 0xFF
                        builder.append('%')
                        builder.append(UPPER_HEX_DIGITS[unsigned ushr 4])
                        builder.append(UPPER_HEX_DIGITS[unsigned and 0x0F])
                    }
                index = codePointEnd
            }
        }
    }
    return builder.toString()
}

private fun Char.isAsciiHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

internal fun lspDocumentUrisEquivalent(left: String, right: String): Boolean =
    canonicalizeLspDocumentUri(left) == canonicalizeLspDocumentUri(right)
