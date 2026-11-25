package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.crash.CrashUploadState
import com.wuxianggujun.tinaide.core.i18n.Strings
import org.junit.Test

class AboutSettingsSectionSupportTest {

    @Test
    fun resolveDeveloperOptionsTap_shouldShowAlreadyEnabledToastWithoutChangingState() {
        val currentState = AboutDeveloperOptionsTapState(
            clickCount = 3,
            lastClickTimeMillis = 1000L,
        )

        val result = AboutSettingsSectionSupport.resolveDeveloperOptionsTap(
            developerOptionsEnabled = true,
            currentState = currentState,
            currentTimeMillis = 2000L,
        )

        assertThat(result.nextState).isEqualTo(currentState)
        assertThat(result.enableDeveloperOptions).isFalse()
        assertThat(result.toast).isEqualTo(
            AboutToastSpec(
                message = AboutMessageSpec(Strings.about_developer_options_enabled)
            )
        )
    }

    @Test
    fun resolveDeveloperOptionsTap_shouldAdvanceCounterAndOnlyWarnNearThreshold() {
        val silentResult = AboutSettingsSectionSupport.resolveDeveloperOptionsTap(
            developerOptionsEnabled = false,
            currentState = AboutDeveloperOptionsTapState(
                clickCount = 1,
                lastClickTimeMillis = 1000L,
            ),
            currentTimeMillis = 2000L,
        )
        assertThat(silentResult.nextState).isEqualTo(
            AboutDeveloperOptionsTapState(
                clickCount = 2,
                lastClickTimeMillis = 2000L,
            )
        )
        assertThat(silentResult.toast).isNull()

        val hintResult = AboutSettingsSectionSupport.resolveDeveloperOptionsTap(
            developerOptionsEnabled = false,
            currentState = silentResult.nextState,
            currentTimeMillis = 2500L,
        )
        assertThat(hintResult.nextState).isEqualTo(
            AboutDeveloperOptionsTapState(
                clickCount = 3,
                lastClickTimeMillis = 2500L,
            )
        )
        assertThat(hintResult.enableDeveloperOptions).isFalse()
        assertThat(hintResult.toast).isEqualTo(
            AboutToastSpec(
                message = AboutMessageSpec(
                    messageRes = Strings.about_enable_developer_hint,
                    formatArgs = listOf(2),
                )
            )
        )
    }

    @Test
    fun resolveDeveloperOptionsTap_shouldResetExpiredCounterAndEnableAtThreshold() {
        val resetResult = AboutSettingsSectionSupport.resolveDeveloperOptionsTap(
            developerOptionsEnabled = false,
            currentState = AboutDeveloperOptionsTapState(
                clickCount = 4,
                lastClickTimeMillis = 1000L,
            ),
            currentTimeMillis = 5001L,
        )
        assertThat(resetResult.nextState).isEqualTo(
            AboutDeveloperOptionsTapState(
                clickCount = 1,
                lastClickTimeMillis = 5001L,
            )
        )
        assertThat(resetResult.toast).isNull()

        val enabledResult = AboutSettingsSectionSupport.resolveDeveloperOptionsTap(
            developerOptionsEnabled = false,
            currentState = AboutDeveloperOptionsTapState(
                clickCount = 4,
                lastClickTimeMillis = 2000L,
            ),
            currentTimeMillis = 3000L,
        )
        assertThat(enabledResult.nextState).isEqualTo(
            AboutDeveloperOptionsTapState(
                clickCount = 0,
                lastClickTimeMillis = 3000L,
            )
        )
        assertThat(enabledResult.enableDeveloperOptions).isTrue()
        assertThat(enabledResult.toast).isEqualTo(
            AboutToastSpec(
                message = AboutMessageSpec(Strings.about_developer_options_enabled_long),
                duration = AboutToastDuration.Long,
            )
        )
    }

    @Test
    fun resolveCrashUploadStatus_shouldExplainDisabledNoneAndRetryStates() {
        val noneSnapshot = CrashUploadState.Snapshot(
            status = CrashUploadState.Status.NONE,
            fileName = "",
            fileMtime = 0L,
            updatedAt = 0L,
            reason = "",
            attemptCount = 0,
        )
        val retrySnapshot = noneSnapshot.copy(status = CrashUploadState.Status.RETRY_PENDING)

        val disabled = AboutSettingsSectionSupport.resolveCrashUploadStatus(
            autoUploadEnabled = false,
            snapshot = retrySnapshot,
        )
        val none = AboutSettingsSectionSupport.resolveCrashUploadStatus(
            autoUploadEnabled = true,
            snapshot = noneSnapshot,
        )
        val retry = AboutSettingsSectionSupport.resolveCrashUploadStatus(
            autoUploadEnabled = true,
            snapshot = retrySnapshot,
        )

        assertThat(disabled.messageRes).isEqualTo(Strings.settings_crash_upload_status_disabled)
        assertThat(disabled.isAttention).isTrue()
        assertThat(none.messageRes).isEqualTo(Strings.settings_crash_upload_status_no_record)
        assertThat(none.isAttention).isFalse()
        assertThat(retry.messageRes).isEqualTo(Strings.crash_upload_status_retry_pending)
        assertThat(retry.isAttention).isTrue()
    }

    @Test
    fun logOperationGuards_shouldBlockDuplicateActions() {
        assertThat(AboutSettingsSectionSupport.shouldStartLogExport(false)).isTrue()
        assertThat(AboutSettingsSectionSupport.shouldStartLogExport(true)).isFalse()

        assertThat(AboutSettingsSectionSupport.shouldShowUploadLogsDialog(false)).isTrue()
        assertThat(AboutSettingsSectionSupport.shouldShowUploadLogsDialog(true)).isFalse()

        assertThat(AboutSettingsSectionSupport.shouldStartLogsUpload(false)).isTrue()
        assertThat(AboutSettingsSectionSupport.shouldStartLogsUpload(true)).isFalse()

        assertThat(AboutSettingsSectionSupport.shouldStartLogsClear(false)).isTrue()
        assertThat(AboutSettingsSectionSupport.shouldStartLogsClear(true)).isFalse()
    }

    @Test
    fun resolveQqGroupOpenUrls_shouldReturnEmptyWhenNotConfigured() {
        val urls = AboutSettingsSectionSupport.resolveQqGroupOpenUrls(
            groupNumber = "",
            joinUrl = "",
        )

        assertThat(urls).isEmpty()
    }

    @Test
    fun resolveQqGroupOpenUrls_shouldPreferQqGroupCardAndKeepFallbackUrl() {
        val urls = AboutSettingsSectionSupport.resolveQqGroupOpenUrls(
            groupNumber = "123456789",
            joinUrl = "https://qm.qq.com/q/example",
        )

        assertThat(urls).containsExactly(
            "mqqapi://card/show_pslcard?src_type=internal&version=1" +
                "&uin=123456789&card_type=group&source=qrcode",
            "https://qm.qq.com/q/example",
        ).inOrder()
    }

    @Test
    fun resolveQqGroupOpenUrls_shouldIgnoreInvalidConfigValues() {
        val urls = AboutSettingsSectionSupport.resolveQqGroupOpenUrls(
            groupNumber = "123&bad=true",
            joinUrl = "file:///sdcard/bad",
        )

        assertThat(urls).isEmpty()
    }
}
