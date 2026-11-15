package com.wuxianggujun.tinaide.core.logging

/**
 * Removes credentials and user-owned paths before an upload that the user did not
 * review line by line.
 *
 * Used by the automatic crash upload both for the structured tombstone text and for
 * every text entry inside the crash log bundle (Timber, logcat, install logs...).
 */
object LogTextSanitizer {
    private const val REDACTED = "<redacted>"
    private const val PRIVATE_PATH = "<private-path>"
    private const val PRIVATE_URI = "<private-uri>"
    private const val CREDENTIAL_KEY =
        "access[_-]?token|refresh[_-]?token|token|api[_-]?key|apikey|authorization|" +
            "password|passwd|secret|cookie|session(?:[_-]?id)?"

    private val urlWithUserInfo = Regex("""(?i)\bhttps?://[^\s/@]+@[^\s<>"']+""")
    private val urlWithQueryOrFragment = Regex("""(?i)\b(https?://[^\s?#<>"']+)[?#][^\s<>"']*""")
    private val privateUri = Regex("""(?i)\b(?:content|file)://[^\s<>"']+""")
    private val jsonCredential = Regex(
        """(?i)(["'](?:$CREDENTIAL_KEY)["']\s*:\s*)["'][^"'\r\n]*["']"""
    )
    private val assignedCredential = Regex(
        """(?i)\b($CREDENTIAL_KEY)\s*[:=]\s*(?:(?:bearer|basic)\s+)?[^\s,;]+"""
    )
    private val standaloneAuthorization = Regex("""(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=-]+""")
    private val externalStoragePath = Regex(
        """(?i)(?:/storage/emulated/\d+|/sdcard|/mnt/media_rw/[^/\s]+)(?:/[^\s:(),\[\]]+)*"""
    )

    fun sanitize(text: String, packageName: String): String {
        if (text.isBlank()) return text

        var sanitized = urlWithUserInfo.replace(text, "<redacted-url>")
        sanitized = urlWithQueryOrFragment.replace(sanitized) { match ->
            "${match.groupValues[1]}?$REDACTED"
        }
        sanitized = privateUri.replace(sanitized, PRIVATE_URI)
        sanitized = jsonCredential.replace(sanitized) { match ->
            "${match.groupValues[1]}\"$REDACTED\""
        }
        sanitized = assignedCredential.replace(sanitized) { match ->
            "${match.groupValues[1]}=$REDACTED"
        }
        sanitized = standaloneAuthorization.replace(sanitized) { match ->
            "${match.groupValues[1]} $REDACTED"
        }
        sanitized = externalStoragePath.replace(sanitized, PRIVATE_PATH)

        if (packageName.isNotBlank()) {
            val appPrivatePath = Regex(
                """/data/(?:data|user/\d+)/${Regex.escape(packageName)}/""" +
                    """(?:files|cache)(?:/[^\s:(),\[\]]+)*"""
            )
            sanitized = appPrivatePath.replace(sanitized, PRIVATE_PATH)
        }
        return sanitized
    }
}
