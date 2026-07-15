package com.wuxianggujun.tinaide.plugin

import android.content.Context
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr

internal enum class PluginCompatibilityStatus {
    COMPATIBLE,
    INVALID_MIN_APP_VERSION,
    HOST_VERSION_UNKNOWN,
    HOST_TOO_OLD,
}

internal data class PluginCompatibilityResult(
    val status: PluginCompatibilityStatus,
    val hostVersion: String?,
    val minAppVersion: String?,
) {
    val isCompatible: Boolean
        get() = status != PluginCompatibilityStatus.HOST_TOO_OLD
}

internal object PluginCompatibility {
    fun evaluate(
        hostVersion: String?,
        minAppVersion: String?,
    ): PluginCompatibilityResult {
        val requiredVersion = minAppVersion?.trim()?.takeIf { it.isNotBlank() }
            ?: return PluginCompatibilityResult(
                status = PluginCompatibilityStatus.COMPATIBLE,
                hostVersion = hostVersion?.trim()?.takeIf { it.isNotBlank() },
                minAppVersion = null,
            )
        if (PluginVersionComparator.compare(requiredVersion, requiredVersion) == null) {
            return PluginCompatibilityResult(
                status = PluginCompatibilityStatus.INVALID_MIN_APP_VERSION,
                hostVersion = hostVersion?.trim()?.takeIf { it.isNotBlank() },
                minAppVersion = requiredVersion,
            )
        }

        val currentVersion = hostVersion?.trim()?.takeIf { it.isNotBlank() }
            ?: return PluginCompatibilityResult(
                status = PluginCompatibilityStatus.HOST_VERSION_UNKNOWN,
                hostVersion = null,
                minAppVersion = requiredVersion,
            )
        val versionOrder = PluginVersionComparator.compare(currentVersion, requiredVersion)
            ?: return PluginCompatibilityResult(
                status = PluginCompatibilityStatus.HOST_VERSION_UNKNOWN,
                hostVersion = currentVersion,
                minAppVersion = requiredVersion,
            )
        return PluginCompatibilityResult(
            status = if (versionOrder < 0) {
                PluginCompatibilityStatus.HOST_TOO_OLD
            } else {
                PluginCompatibilityStatus.COMPATIBLE
            },
            hostVersion = currentVersion,
            minAppVersion = requiredVersion,
        )
    }

    fun evaluate(
        context: Context,
        manifest: PluginManifest,
    ): PluginCompatibilityResult = evaluate(
        hostVersion = resolveCurrentAppVersion(context),
        minAppVersion = manifest.minAppVersion,
    )

    fun requireCompatible(
        context: Context,
        manifest: PluginManifest,
    ) {
        val result = evaluate(context, manifest)
        require(result.isCompatible) {
            Strings.plugin_diagnostic_min_app_version_unsupported.strOr(
                context,
                checkNotNull(result.minAppVersion),
                result.hostVersion.orEmpty(),
            )
        }
    }

    fun resolveCurrentAppVersion(context: Context): String? = runCatching {
        context.packageManager
            .getPackageInfo(context.packageName, 0)
            .versionName
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
