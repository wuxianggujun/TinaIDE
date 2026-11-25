package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import androidx.annotation.StringRes
import com.wuxianggujun.tinaide.core.crash.CrashUploadState
import com.wuxianggujun.tinaide.core.crash.CrashUploadStatusTextResolver
import com.wuxianggujun.tinaide.core.i18n.Strings

internal data class AboutMessageSpec(
    @param:StringRes @get:StringRes val messageRes: Int,
    val formatArgs: List<Any> = emptyList(),
)

internal enum class AboutToastDuration {
    Short,
    Long,
}

internal data class AboutToastSpec(
    val message: AboutMessageSpec,
    val duration: AboutToastDuration = AboutToastDuration.Short,
)

internal data class AboutDeveloperOptionsTapState(
    val clickCount: Int,
    val lastClickTimeMillis: Long,
)

internal data class AboutDeveloperOptionsTapResult(
    val nextState: AboutDeveloperOptionsTapState,
    val enableDeveloperOptions: Boolean = false,
    val toast: AboutToastSpec? = null,
)

internal data class AboutCrashUploadStatusSpec(
    @param:StringRes @get:StringRes val messageRes: Int,
    val isAttention: Boolean = false,
)

internal object AboutSettingsSectionSupport {
    private const val DEVELOPER_OPTIONS_TAP_THRESHOLD = 5
    private const val DEVELOPER_OPTIONS_TAP_RESET_WINDOW_MILLIS = 3000L
    private const val QQ_GROUP_CARD_URL_TEMPLATE =
        "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=%s&card_type=group&source=qrcode"
    private val qqGroupNumberPattern = Regex("^\\d+$")
    private val supportedQqGroupJoinUrlPrefixes = listOf(
        "https://",
        "http://",
        "mqqapi://",
        "mqqopensdkapi://",
    )

    fun resolveDeveloperOptionsTap(
        developerOptionsEnabled: Boolean,
        currentState: AboutDeveloperOptionsTapState,
        currentTimeMillis: Long,
    ): AboutDeveloperOptionsTapResult {
        if (developerOptionsEnabled) {
            return AboutDeveloperOptionsTapResult(
                nextState = currentState,
                toast = AboutToastSpec(
                    message = AboutMessageSpec(Strings.about_developer_options_enabled)
                ),
            )
        }

        val clickCount = if (
            currentTimeMillis - currentState.lastClickTimeMillis >
            DEVELOPER_OPTIONS_TAP_RESET_WINDOW_MILLIS
        ) {
            0
        } else {
            currentState.clickCount
        }
        val nextClickCount = clickCount + 1
        val nextState = AboutDeveloperOptionsTapState(
            clickCount = nextClickCount,
            lastClickTimeMillis = currentTimeMillis,
        )
        val remaining = DEVELOPER_OPTIONS_TAP_THRESHOLD - nextClickCount

        return when {
            nextClickCount >= DEVELOPER_OPTIONS_TAP_THRESHOLD -> {
                AboutDeveloperOptionsTapResult(
                    nextState = nextState.copy(clickCount = 0),
                    enableDeveloperOptions = true,
                    toast = AboutToastSpec(
                        message = AboutMessageSpec(Strings.about_developer_options_enabled_long),
                        duration = AboutToastDuration.Long,
                    ),
                )
            }

            remaining <= 2 -> {
                AboutDeveloperOptionsTapResult(
                    nextState = nextState,
                    toast = AboutToastSpec(
                        message = AboutMessageSpec(
                            messageRes = Strings.about_enable_developer_hint,
                            formatArgs = listOf(remaining),
                        )
                    ),
                )
            }

            else -> {
                AboutDeveloperOptionsTapResult(nextState = nextState)
            }
        }
    }

    fun resolveCrashUploadStatus(
        autoUploadEnabled: Boolean,
        snapshot: CrashUploadState.Snapshot,
    ): AboutCrashUploadStatusSpec {
        if (!autoUploadEnabled) {
            return AboutCrashUploadStatusSpec(
                messageRes = Strings.settings_crash_upload_status_disabled,
                isAttention = true,
            )
        }

        if (snapshot.status == CrashUploadState.Status.NONE) {
            return AboutCrashUploadStatusSpec(
                messageRes = Strings.settings_crash_upload_status_no_record,
                isAttention = false,
            )
        }

        val statusText = CrashUploadStatusTextResolver.resolve(snapshot.status)
        return AboutCrashUploadStatusSpec(
            messageRes = statusText.messageRes,
            isAttention = statusText.isAttention,
        )
    }

    fun shouldStartLogExport(isExportingLogs: Boolean): Boolean = !isExportingLogs

    fun shouldShowUploadLogsDialog(isUploadingLogs: Boolean): Boolean = !isUploadingLogs

    fun shouldStartLogsUpload(isUploadingLogs: Boolean): Boolean = !isUploadingLogs

    fun shouldStartLogsClear(isClearingLogs: Boolean): Boolean = !isClearingLogs

    fun resolveQqGroupOpenUrls(
        groupNumber: String,
        joinUrl: String,
    ): List<String> {
        val normalizedGroupNumber = groupNumber.trim()
        val normalizedJoinUrl = joinUrl.trim()

        return buildList {
            if (qqGroupNumberPattern.matches(normalizedGroupNumber)) {
                add(QQ_GROUP_CARD_URL_TEMPLATE.format(normalizedGroupNumber))
            }
            if (
                normalizedJoinUrl.isNotEmpty() &&
                supportedQqGroupJoinUrlPrefixes.any { prefix ->
                    normalizedJoinUrl.startsWith(prefix, ignoreCase = true)
                }
            ) {
                add(normalizedJoinUrl)
            }
        }
    }
}
