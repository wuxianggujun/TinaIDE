package com.wuxianggujun.tinaide.core.logging

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LogExportPolicyTest {

    @Test
    fun `manual server upload includes project and runtime diagnostics after confirmation`() {
        val policy = LogExportPolicy.from(LogExportProfile.SERVER_UPLOAD)

        assertThat(policy.includes(LogBundleSource.BUILD_LOGS)).isTrue()
        assertThat(policy.includes(LogBundleSource.ROOTFS_PACKAGE_MANAGER_LOGS)).isTrue()
        assertThat(policy.includes(LogBundleSource.PROOT_LOGS)).isTrue()
        val consentRequiredPrivacyClasses = setOf(LogExportPrivacyClass.USER_PROJECT, LogExportPrivacyClass.USER_RUNTIME)
        assertThat(policy.sources.map { it.privacyClass }.toSet().intersect(consentRequiredPrivacyClasses))
            .containsExactly(LogExportPrivacyClass.USER_PROJECT, LogExportPrivacyClass.USER_RUNTIME)
    }

    @Test
    fun `server upload keeps app process logcat without global fallback`() {
        val policy = LogExportPolicy.from(LogExportProfile.SERVER_UPLOAD)

        assertThat(policy.includes(LogBundleSource.LOGCAT)).isTrue()
        assertThat(policy.logcatEntryName).isEqualTo("app_process_logcat.txt")
        assertThat(policy.allowGlobalLogcatFallback).isFalse()
        assertThat(policy.includeUserRuntimeTombstones).isFalse()
    }

    @Test
    fun `server and crash upload include the crashed process and crash buffer`() {
        for (profile in listOf(LogExportProfile.SERVER_UPLOAD, LogExportProfile.CRASH_UPLOAD)) {
            val policy = LogExportPolicy.from(profile)
            assertThat(policy.includeRecentHostLogcat).isTrue()
            assertThat(policy.includeCrashBuffer).isTrue()
        }
        val local = LogExportPolicy.from(LogExportProfile.LOCAL_EXPORT)
        assertThat(local.includeCrashBuffer).isFalse()
    }

    @Test
    fun `local export keeps complete user controlled diagnostics`() {
        val policy = LogExportPolicy.from(LogExportProfile.LOCAL_EXPORT)

        assertThat(LogBundleSource.values().all { policy.includes(it) }).isTrue()
        assertThat(policy.logcatEntryName).isEqualTo("logcat.txt")
        assertThat(policy.allowGlobalLogcatFallback).isTrue()
        assertThat(policy.includeUserRuntimeTombstones).isTrue()
    }

    @Test
    fun `manifest records privacy gate and excluded server sources`() {
        val policy = LogExportPolicy.from(LogExportProfile.SERVER_UPLOAD)
        val manifest = LogExportManifestBuilder.build(policy, generatedAt = "2026-04-29 10:00:00")

        assertThat(manifest).contains("profile=SERVER_UPLOAD")
        assertThat(manifest).contains("global_logcat_fallback=false")
        assertThat(manifest).contains("include_user_runtime_tombstones=false")
        assertThat(manifest).contains("requires_explicit_user_consent=true")
        assertThat(manifest).contains("allowed_privacy_classes=EXPORT_METADATA, APP_DIAGNOSTIC, HOST_CRASH, USER_PROJECT, USER_RUNTIME")
        assertThat(manifest).contains("consent_required_privacy_classes=USER_PROJECT, USER_RUNTIME")
        assertThat(manifest).contains("excluded_privacy_classes=NONE")
        assertThat(manifest).contains("- name=BUILD_LOGS")
        assertThat(manifest).contains("  included=true")
    }

    @Test
    fun `crash upload keeps full ide diagnostics without consent sources`() {
        val policy = LogExportPolicy.from(LogExportProfile.CRASH_UPLOAD)

        // 崩溃自动上报必须覆盖 IDE 自身的全部诊断日志。
        assertThat(policy.includes(LogBundleSource.EXPORT_MANIFEST)).isTrue()
        assertThat(policy.includes(LogBundleSource.LOGCAT)).isTrue()
        assertThat(policy.includes(LogBundleSource.APP_INFO)).isTrue()
        assertThat(policy.includes(LogBundleSource.DEVICE_INFO)).isTrue()
        assertThat(policy.includes(LogBundleSource.CRASH_LOGS)).isTrue()
        assertThat(policy.includes(LogBundleSource.INSTALL_LOGS)).isTrue()
        assertThat(policy.includes(LogBundleSource.INSTALL_LOG_SNAPSHOT)).isTrue()
        assertThat(policy.includes(LogBundleSource.TOMBSTONES)).isTrue()
        assertThat(policy.includes(LogBundleSource.TIMBER_LOGS)).isTrue()

        // 但不得包含需要用户显式授权的项目/运行时数据。
        assertThat(policy.includes(LogBundleSource.BUILD_LOGS)).isFalse()
        assertThat(policy.includes(LogBundleSource.ROOTFS_PACKAGE_MANAGER_LOGS)).isFalse()
        assertThat(policy.includes(LogBundleSource.PROOT_LOGS)).isFalse()

        assertThat(policy.logcatEntryName).isEqualTo("app_process_logcat.txt")
        assertThat(policy.allowGlobalLogcatFallback).isFalse()
        assertThat(policy.includeUserRuntimeTombstones).isFalse()
    }

    @Test
    fun `crash upload manifest states it is automatic`() {
        val policy = LogExportPolicy.from(LogExportProfile.CRASH_UPLOAD)
        val manifest = LogExportManifestBuilder.build(policy, generatedAt = "2026-04-29 10:00:00")

        assertThat(manifest).contains("profile=CRASH_UPLOAD")
        assertThat(manifest).contains("requires_explicit_user_consent=false")
        assertThat(manifest).contains("automatic_crash_upload=true")
        assertThat(manifest).contains("excluded_sources=BUILD_LOGS, ROOTFS_PACKAGE_MANAGER_LOGS, PROOT_LOGS")
    }
}
