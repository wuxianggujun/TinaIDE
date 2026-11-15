package com.wuxianggujun.tinaide.core.logging

/**
 * 日志导出场景。
 *
 * - [LOCAL_EXPORT]：用户主动分享，可包含完整诊断数据。
 * - [SERVER_UPLOAD]：设置页手动反馈，用户确认后可包含构建、PRoot/rootfs 等诊断日志。
 * - [CRASH_UPLOAD]：崩溃后自动上报。只包含 IDE 自身诊断（Timber、logcat、安装日志、
 *   墓碑等），不包含需要显式授权的用户项目/运行时数据。
 */
enum class LogExportProfile {
    LOCAL_EXPORT,
    SERVER_UPLOAD,
    CRASH_UPLOAD,
}

internal enum class LogExportPrivacyClass {
    EXPORT_METADATA,
    APP_DIAGNOSTIC,
    HOST_CRASH,
    USER_PROJECT,
    USER_RUNTIME,
}

internal val serverUploadAllowedPrivacyClasses = setOf(
    LogExportPrivacyClass.EXPORT_METADATA,
    LogExportPrivacyClass.APP_DIAGNOSTIC,
    LogExportPrivacyClass.HOST_CRASH,
    LogExportPrivacyClass.USER_PROJECT,
    LogExportPrivacyClass.USER_RUNTIME,
)

internal val serverUploadConsentRequiredPrivacyClasses = setOf(
    LogExportPrivacyClass.USER_PROJECT,
    LogExportPrivacyClass.USER_RUNTIME,
)

internal enum class LogBundleSource(
    val entryName: String,
    val privacyClass: LogExportPrivacyClass,
    val includedInLocalExport: Boolean,
    val includedInServerUpload: Boolean,
    val localExportReason: String,
    val serverUploadReason: String,
    val serverExcludedReason: String,
) {
    EXPORT_MANIFEST(
        entryName = "export_manifest.txt",
        privacyClass = LogExportPrivacyClass.EXPORT_METADATA,
        includedInLocalExport = true,
        includedInServerUpload = true,
        localExportReason = "Documents the local export collection policy.",
        serverUploadReason = "Documents the server upload collection policy.",
        serverExcludedReason = "Never excluded."
    ),
    LOGCAT(
        entryName = "<profile-logcat-entry>",
        privacyClass = LogExportPrivacyClass.APP_DIAGNOSTIC,
        includedInLocalExport = true,
        includedInServerUpload = true,
        localExportReason = "Local export may include full logcat for user-controlled troubleshooting.",
        serverUploadReason = "Server upload keeps recent TinaIDE process logcat by pid to capture non-Timber diagnostics without global fallback.",
        serverExcludedReason = "Not excluded."
    ),
    APP_INFO(
        entryName = "app_info.txt",
        privacyClass = LogExportPrivacyClass.APP_DIAGNOSTIC,
        includedInLocalExport = true,
        includedInServerUpload = true,
        localExportReason = "App version and package metadata help diagnose compatibility issues.",
        serverUploadReason = "App version and package metadata are required for TinaIDE issue triage.",
        serverExcludedReason = "Not excluded."
    ),
    DEVICE_INFO(
        entryName = "device_info.txt",
        privacyClass = LogExportPrivacyClass.APP_DIAGNOSTIC,
        includedInLocalExport = true,
        includedInServerUpload = true,
        localExportReason = "Device and Android version metadata help reproduce local issues.",
        serverUploadReason = "Device and Android version metadata help reproduce TinaIDE issues.",
        serverExcludedReason = "Not excluded."
    ),
    CRASH_LOGS(
        entryName = "crashes/*.log",
        privacyClass = LogExportPrivacyClass.HOST_CRASH,
        includedInLocalExport = true,
        includedInServerUpload = true,
        localExportReason = "Host-side crash logs are useful for local troubleshooting.",
        serverUploadReason = "Host-side crash logs are TinaIDE diagnostics.",
        serverExcludedReason = "Not excluded."
    ),
    BUILD_LOGS(
        entryName = "build_logs/*.log",
        privacyClass = LogExportPrivacyClass.USER_PROJECT,
        includedInLocalExport = true,
        includedInServerUpload = true,
        localExportReason = "Build logs are kept in local export so users can decide whether to share project diagnostics.",
        serverUploadReason = "Build logs are included in manual server upload so TinaIDE can diagnose build and toolchain issues after explicit user confirmation.",
        serverExcludedReason = "Build logs can contain user project paths, compiler output, source snippets, or private dependency names."
    ),
    INSTALL_LOGS(
        entryName = "install_logs/*",
        privacyClass = LogExportPrivacyClass.APP_DIAGNOSTIC,
        includedInLocalExport = true,
        includedInServerUpload = true,
        localExportReason = "Installation logs diagnose TinaIDE package/setup issues.",
        serverUploadReason = "Installation logs diagnose TinaIDE package/setup issues.",
        serverExcludedReason = "Not excluded."
    ),
    INSTALL_LOG_SNAPSHOT(
        entryName = "install_log_snapshot.txt",
        privacyClass = LogExportPrivacyClass.APP_DIAGNOSTIC,
        includedInLocalExport = true,
        includedInServerUpload = true,
        localExportReason = "InstallLogManager snapshot preserves recent setup context.",
        serverUploadReason = "InstallLogManager snapshot preserves recent TinaIDE setup context.",
        serverExcludedReason = "Not excluded."
    ),
    ROOTFS_PACKAGE_MANAGER_LOGS(
        entryName = "rootfs_logs/*",
        privacyClass = LogExportPrivacyClass.USER_RUNTIME,
        includedInLocalExport = true,
        includedInServerUpload = true,
        localExportReason = "Rootfs package-manager logs stay local for user-controlled Linux runtime diagnostics.",
        serverUploadReason = "Rootfs package-manager logs are included in manual server upload to diagnose TinaIDE-managed Linux environment issues after explicit user confirmation.",
        serverExcludedReason = "Rootfs logs belong to the user's Linux/runtime environment and may reveal installed packages or repositories."
    ),
    PROOT_LOGS(
        entryName = "proot_logs/*.log",
        privacyClass = LogExportPrivacyClass.USER_RUNTIME,
        includedInLocalExport = true,
        includedInServerUpload = true,
        localExportReason = "PRoot logs stay local for user-controlled runtime diagnostics.",
        serverUploadReason = "PRoot logs are included in manual server upload to diagnose runtime startup and isolation issues after explicit user confirmation.",
        serverExcludedReason = "PRoot logs belong to user runtime sessions and can contain commands, paths, or project output."
    ),
    TOMBSTONES(
        entryName = "tombstones/*",
        privacyClass = LogExportPrivacyClass.HOST_CRASH,
        includedInLocalExport = true,
        includedInServerUpload = true,
        localExportReason = "Local export includes tombstones so users can inspect both host and runtime native crashes.",
        serverUploadReason = "Server upload includes host tombstones only; user runtime tombstones are filtered before packaging.",
        serverExcludedReason = "Not excluded."
    ),
    TIMBER_LOGS(
        entryName = "timber_logs/tinaide_*.log",
        privacyClass = LogExportPrivacyClass.APP_DIAGNOSTIC,
        includedInLocalExport = true,
        includedInServerUpload = true,
        localExportReason = "Timber files are the structured TinaIDE diagnostic log source.",
        serverUploadReason = "Timber files are the primary structured TinaIDE diagnostic log source.",
        serverExcludedReason = "Not excluded."
    );

    fun includedIn(profile: LogExportProfile): Boolean = when (profile) {
        LogExportProfile.LOCAL_EXPORT -> includedInLocalExport
        LogExportProfile.SERVER_UPLOAD -> includedInServerUpload
        // 崩溃自动上报没有用户逐次确认，必须排除需要显式授权的用户项目/运行时数据。
        LogExportProfile.CRASH_UPLOAD ->
            includedInServerUpload && privacyClass !in serverUploadConsentRequiredPrivacyClasses
    }

    fun manifestEntryName(logcatEntryName: String): String = if (this == LOGCAT) logcatEntryName else entryName

    fun reasonFor(profile: LogExportProfile): String = when (profile) {
        LogExportProfile.LOCAL_EXPORT -> localExportReason
        LogExportProfile.SERVER_UPLOAD -> if (includedInServerUpload) serverUploadReason else serverExcludedReason
        LogExportProfile.CRASH_UPLOAD -> when {
            !includedInServerUpload -> serverExcludedReason
            privacyClass in serverUploadConsentRequiredPrivacyClasses ->
                "Excluded from automatic crash upload because this source requires explicit user consent."
            else -> serverUploadReason
        }
    }
}

internal data class LogExportPolicy(
    val profile: LogExportProfile,
    val logcatEntryName: String,
    val allowGlobalLogcatFallback: Boolean,
    val includeUserRuntimeTombstones: Boolean,
    /** Include persisted pids of processes that already exited (for example the crashed host). */
    val includeRecentHostLogcat: Boolean,
    /** Include the package-filtered Android crash buffer. */
    val includeCrashBuffer: Boolean,
    val sources: Set<LogBundleSource>,
) {
    init {
        if (profile != LogExportProfile.LOCAL_EXPORT) {
            check(!allowGlobalLogcatFallback) {
                "Server upload policy must not use global logcat fallback."
            }
            check(!includeUserRuntimeTombstones) {
                "Server upload policy must not include user runtime tombstones."
            }
            val disallowedSources = sources.filterNot { it.privacyClass in serverUploadAllowedPrivacyClasses }
            check(disallowedSources.isEmpty()) {
                "Server upload policy includes disallowed sources: ${disallowedSources.joinToString { it.name }}"
            }
            if (profile == LogExportProfile.CRASH_UPLOAD) {
                val consentSources = sources.filter {
                    it.privacyClass in serverUploadConsentRequiredPrivacyClasses
                }
                check(consentSources.isEmpty()) {
                    "Crash upload policy includes consent-required sources: ${consentSources.joinToString { it.name }}"
                }
            }
        }
    }

    fun includes(source: LogBundleSource): Boolean = source in sources

    companion object {
        fun from(profile: LogExportProfile): LogExportPolicy = when (profile) {
            LogExportProfile.LOCAL_EXPORT -> LogExportPolicy(
                profile = profile,
                logcatEntryName = "logcat.txt",
                allowGlobalLogcatFallback = true,
                includeUserRuntimeTombstones = true,
                includeRecentHostLogcat = false,
                includeCrashBuffer = false,
                sources = resolveSources(profile),
            )
            LogExportProfile.SERVER_UPLOAD, LogExportProfile.CRASH_UPLOAD -> LogExportPolicy(
                profile = profile,
                logcatEntryName = "app_process_logcat.txt",
                allowGlobalLogcatFallback = false,
                includeUserRuntimeTombstones = false,
                includeRecentHostLogcat = true,
                includeCrashBuffer = true,
                sources = resolveSources(profile),
            )
        }

        private fun resolveSources(profile: LogExportProfile): Set<LogBundleSource> = LogBundleSource.values()
            .filter { it.includedIn(profile) }
            .toSet()
    }
}

internal object LogExportManifestBuilder {
    fun build(policy: LogExportPolicy, generatedAt: String): String {
        val allSources = LogBundleSource.values().toList()
        val includedSources = allSources.filter { policy.includes(it) }
        val excludedSources = allSources.filterNot { policy.includes(it) }
        val excludedPrivacyClasses = LogExportPrivacyClass.values()
            .filterNot { it in serverUploadAllowedPrivacyClasses }

        return buildString {
            appendLine("=== TinaIDE Log Export Manifest ===")
            appendLine("profile=${policy.profile}")
            appendLine("generated_at=$generatedAt")
            appendLine("logcat_entry=${policy.logcatEntryName}")
            appendLine("global_logcat_fallback=${policy.allowGlobalLogcatFallback}")
            appendLine("recent_host_logcat=${policy.includeRecentHostLogcat}")
            appendLine("crash_buffer_logcat=${policy.includeCrashBuffer}")
            appendLine("text_sanitized_on_upload=${policy.profile != LogExportProfile.LOCAL_EXPORT}")
            appendLine("include_user_runtime_tombstones=${policy.includeUserRuntimeTombstones}")
            appendLine("included_sources=${includedSources.joinToString(", ") { it.name }}")
            appendLine("excluded_sources=${excludedSources.joinToString(", ") { it.name }.ifBlank { "NONE" }}")
            appendLine()
            appendLine("[server_upload_privacy_gate]")
            appendLine("requires_explicit_user_consent=${policy.profile == LogExportProfile.SERVER_UPLOAD}")
            appendLine("automatic_crash_upload=${policy.profile == LogExportProfile.CRASH_UPLOAD}")
            appendLine("allowed_privacy_classes=${serverUploadAllowedPrivacyClasses.joinToString(", ") { it.name }}")
            appendLine("consent_required_privacy_classes=${serverUploadConsentRequiredPrivacyClasses.joinToString(", ") { it.name }}")
            appendLine("excluded_privacy_classes=${excludedPrivacyClasses.joinToString(", ") { it.name }.ifBlank { "NONE" }}")
            appendLine("timber_role=primary_structured_tinaide_diagnostic_source")
            appendLine("logcat_role=supplemental_tinaide_process_sources_by_pid")
            appendLine("user_runtime_tombstone_filter=${!policy.includeUserRuntimeTombstones}")
            appendLine()
            appendLine("[sources]")
            for (source in allSources) {
                appendLine("- name=${source.name}")
                appendLine("  included=${policy.includes(source)}")
                appendLine("  entry=${source.manifestEntryName(policy.logcatEntryName)}")
                appendLine("  privacy_class=${source.privacyClass}")
                appendLine("  local_export=${source.includedInLocalExport}")
                appendLine("  server_upload=${source.includedInServerUpload}")
                appendLine("  reason=${source.reasonFor(policy.profile)}")
            }
        }
    }
}
