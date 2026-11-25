package com.wuxianggujun.tinaide.ui.activity

import com.wuxianggujun.tinaide.core.util.CrashLogPrivacyClassifier

internal enum class CrashScreenOrigin {
    HOST_APP,
    USER_PROGRAM,
    COMPONENT;

    val canRestartIde: Boolean get() = this == HOST_APP
    val canUploadToHost: Boolean get() = this == HOST_APP
}

/** Structured process identity is authoritative; unknown processes never gain host actions. */
internal fun resolveCrashScreenOrigin(
    packageName: String,
    processName: String,
    requestedSource: String?,
    report: String,
): CrashScreenOrigin {
    val process = processName.trim()
    if (process.isNotEmpty()) {
        return when {
            CrashLogPrivacyClassifier.isUserRuntimeProcess(packageName, process) -> CrashScreenOrigin.USER_PROGRAM
            CrashLogPrivacyClassifier.isHostAppProcess(packageName, process) -> CrashScreenOrigin.HOST_APP
            else -> CrashScreenOrigin.COMPONENT
        }
    }
    // Legacy tags may preserve user presentation, but never grant host actions without a process identity.
    return when {
        requestedSource == CrashActivity.SOURCE_USER_NATIVE -> CrashScreenOrigin.USER_PROGRAM
        CrashLogPrivacyClassifier.isUserRuntimeCrash(packageName, report) -> CrashScreenOrigin.USER_PROGRAM
        else -> CrashScreenOrigin.COMPONENT
    }
}

private const val MAX_SUMMARY_SCAN_CHARS = 16_384
private val CRASH_SIGNAL_PATTERN = Regex("""(?m)^[\t ]*signal[\t ]+\d+[\t ]+\((SIG[A-Z0-9]+)\)""")
private val CRASH_EXCEPTION_PATTERN = Regex("""(?m)^[\t ]*([\w.$]+(?:Exception|Error))(?::|$)""")

/** Show a short technical identifier, never device metadata, paths, or a fabricated diagnosis. */
internal fun crashSummaryIdentifier(report: String): String? {
    val preview = report.take(MAX_SUMMARY_SCAN_CHARS)
    return CRASH_SIGNAL_PATTERN.find(preview)?.groupValues?.get(1)
        ?: CRASH_EXCEPTION_PATTERN.find(preview)?.groupValues?.get(1)?.substringAfterLast('.')
}
