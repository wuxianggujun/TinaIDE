package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import android.content.Context
import android.net.Uri
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.plugin.PluginDiagnosticsReport
import com.wuxianggujun.tinaide.plugin.PluginDoctor
import com.wuxianggujun.tinaide.plugin.PluginManager
import com.wuxianggujun.tinaide.plugin.PluginManifest
import com.wuxianggujun.tinaide.plugin.ZipUtils
import com.wuxianggujun.tinaide.plugin.script.PluginPermission
import com.wuxianggujun.tinaide.plugin.script.PluginPermissionManager
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

data class PendingPluginInstall(
    val tempFile: File,
    val manifest: PluginManifest,
    val permissions: Set<PluginPermission>,
    val diagnosticsReport: PluginDiagnosticsReport,
) {
    val hasPreflightWarnings: Boolean
        get() = PluginInstallHelperSupport.hasPreflightWarnings(diagnosticsReport)
}

sealed interface PluginInstallPreview {
    data class Ready(val pendingInstall: PendingPluginInstall) : PluginInstallPreview

    data class Blocked(
        val tempFile: File,
        val diagnosticsReport: PluginDiagnosticsReport,
    ) : PluginInstallPreview

    data class Failed(val message: String) : PluginInstallPreview
}

data class PluginInstallOutcome(
    val message: String,
    val manifest: PluginManifest?
)

suspend fun previewPluginInstall(
    context: Context,
    uri: Uri
): PluginInstallPreview = withContext(Dispatchers.IO) {
    val destFile = PluginInstallHelperSupport.buildPreviewTempFile(
        cacheDir = context.cacheDir,
        lastPathSegment = uri.lastPathSegment,
        timestampMillis = System.currentTimeMillis(),
    )

    runCatching {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: uri.resolveFileUriOrNull()?.inputStream()
        val copiedWithinLimit = inputStream?.use { input ->
            destFile.outputStream().use { output ->
                PluginInstallHelperSupport.copyAtMost(
                    input = input,
                    output = output,
                    maxBytes = ZipUtils.MAX_PACKAGE_BYTES,
                )
            }
        } ?: return@runCatching PluginInstallPreview.Failed(
            Strings.plugin_error_cannot_read_file.strOr(context)
        )
        require(copiedWithinLimit) { Strings.plugin_error_package_too_large.strOr(context) }

        createPluginInstallPreview(context, destFile)
    }.getOrElse { throwable ->
        PluginInstallPreview.Failed(
            throwable.message ?: throwable::class.simpleName
                ?: Strings.plugin_error_install_failed.strOr(context)
        )
    }.also { preview ->
        if (preview is PluginInstallPreview.Failed) {
            runCatching { destFile.delete() }
        }
    }
}

internal fun createPluginInstallPreview(
    context: Context,
    pluginFile: File,
): PluginInstallPreview {
    val diagnosticsReport = inspectPluginArchive(context, pluginFile)
    if (PluginInstallHelperSupport.shouldBlockPreflightInstall(diagnosticsReport)) {
        return PluginInstallPreview.Blocked(pluginFile, diagnosticsReport)
    }

    val manifest = PluginInstallHelperSupport.readManifestFromZip(pluginFile)
        ?: return PluginInstallPreview.Failed(
            Strings.plugin_error_missing_manifest.strOr(context, PluginManager.MANIFEST_FILE_NAME)
        )
    val permissions = PluginPermission.parseList(manifest.permissions)

    return PluginInstallPreview.Ready(
        PendingPluginInstall(
            tempFile = pluginFile,
            manifest = manifest,
            permissions = permissions,
            diagnosticsReport = diagnosticsReport,
        )
    )
}

suspend fun finishPluginInstall(
    context: Context,
    pluginManager: PluginManager,
    pluginFile: File,
    toastPluginsInstalledTemplate: String,
    toastPluginsInstallFailedTemplate: String,
    permissionManager: PluginPermissionManager? = null,
    permissions: Set<PluginPermission> = emptySet(),
    permissionPluginId: String? = null,
): PluginInstallOutcome = withContext(NonCancellable + Dispatchers.IO) {
    try {
        val permissionOwnerId = permissionManager?.let {
            requireNotNull(permissionPluginId) { "Permission plugin id is required when granting permissions" }
        }
        val previousGrants = if (permissionManager != null && permissionOwnerId != null) {
            permissionManager.getGrantedPermissions(permissionOwnerId)
        } else {
            null
        }
        val result = runCatching {
            if (permissionManager != null && permissionOwnerId != null && permissions.isNotEmpty()) {
                permissionManager.grantPermissions(permissionOwnerId, permissions)
            }
            pluginManager.install(pluginFile).getOrThrow().manifest
        }
        if (result.isFailure && permissionManager != null && permissionOwnerId != null && previousGrants != null) {
            val installError = checkNotNull(result.exceptionOrNull())
            runCatching { permissionManager.replacePermissions(permissionOwnerId, previousGrants) }
                .onFailure(installError::addSuppressed)
        }
        PluginInstallHelperSupport.buildInstallOutcome(
            result = result,
            installedTemplate = toastPluginsInstalledTemplate,
            failedTemplate = toastPluginsInstallFailedTemplate,
        )
    } finally {
        runCatching { pluginFile.delete() }
    }
}

private fun inspectPluginArchive(
    context: Context,
    zipFile: File,
): PluginDiagnosticsReport {
    val tempDir = File(context.cacheDir, "plugin_preflight_${UUID.randomUUID()}")
    return try {
        ZipUtils.unzipToDirectory(zipFile, tempDir)
        PluginDoctor.inspectDirectory(context, tempDir)
    } finally {
        runCatching { tempDir.deleteRecursively() }
    }
}

private fun Uri.resolveFileUriOrNull(): File? {
    if (!scheme.equals("file", ignoreCase = true)) return null
    val rawPath = path ?: return null
    val normalizedPath = if (
        rawPath.length >= 3 &&
        rawPath[0] == '/' &&
        rawPath[1].isLetter() &&
        rawPath[2] == ':'
    ) {
        rawPath.drop(1)
    } else {
        rawPath
    }
    return File(normalizedPath).takeIf { it.isFile }
}
