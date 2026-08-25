package com.wuxianggujun.tinaide.ui.compose.components

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.wuxianggujun.tinaide.core.apkbuilder.ApkBuildConfig
import com.wuxianggujun.tinaide.core.apkbuilder.ApkBuilder
import com.wuxianggujun.tinaide.core.apkbuilder.ApkKeyStoreManager
import com.wuxianggujun.tinaide.core.apkbuilder.ApkSigningConfig
import com.wuxianggujun.tinaide.core.apkbuilder.ApkTemplateType
import com.wuxianggujun.tinaide.core.apkbuilder.DebugKeyStore
import com.wuxianggujun.tinaide.core.apkbuilder.NativeLibraryAbiDetector
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.packages.model.GUIPackage
import com.wuxianggujun.tinaide.storage.ExternalFileIntents
import com.wuxianggujun.tinaide.storage.ProjectDirStructure
import com.wuxianggujun.tinaide.ui.apk.ApkExportTemplateOption
import com.wuxianggujun.tinaide.ui.runtime.NativeLibraryDependencyHints
import java.io.File
import java.security.SignatureException
import java.security.UnrecoverableKeyException
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * APK package dialog permission UI, signing helpers, and install intent utilities.
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ApkPermissionPickerSheet(
    selected: Set<String>,
    onSelectedChange: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = stringResource(Strings.apk_builder_permissions_sheet_title),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.size(8.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(apkPermissionOptions, key = { it.permission }) { option ->
                    ApkPermissionRow(
                        option = option,
                        checked = option.permission in selected,
                        onCheckedChange = { enabled ->
                            onSelectedChange(
                                selected.toMutableSet().apply {
                                    if (enabled) add(option.permission) else remove(option.permission)
                                }
                            )
                        }
                    )
                }
            }
            Spacer(Modifier.size(16.dp))
        }
    }
}

@Composable
internal fun ApkPermissionRow(
    option: ApkPermissionOption,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var expanded by remember(option.permission) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable { expanded = !expanded }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = stringResource(option.labelRes))
                if (option.isHighRisk) {
                    Spacer(Modifier.width(8.dp))
                    TinaStatusBadge(
                        text = stringResource(Strings.apk_builder_permission_high_risk_tag),
                        status = BadgeStatus.ERROR
                    )
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    Text(
                        text = option.permission,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    option.maxSdkVersion?.let { maxSdk ->
                        Text(
                            text = Strings.apk_builder_permission_max_sdk.strOr(context, maxSdk),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        Icon(
            imageVector = Icons.Default.ExpandMore,
            contentDescription = null,
            modifier = Modifier
                .rotate(if (expanded) 180f else 0f)
                .clickable { expanded = !expanded },
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

internal fun resolveManagedKeyStoreDir(outputDir: File, fallbackRoot: File): File {
    val projectRoot = outputDir.parentFile?.parentFile
    return if (projectRoot != null) {
        ProjectDirStructure.getKeystoreDir(projectRoot.absolutePath)
    } else {
        File(fallbackRoot, "apk_keystores")
    }
}

internal fun resolveManagedIconDir(outputDir: File, fallbackRoot: File): File {
    val projectRoot = outputDir.parentFile?.parentFile
    return if (projectRoot != null) {
        ProjectDirStructure.getApkExportIconsDir(projectRoot.absolutePath)
    } else {
        File(fallbackRoot, "apk_icons")
    }
}

internal fun resolveManagedRuntimeLibraryDir(outputDir: File, fallbackRoot: File): File {
    val projectRoot = outputDir.parentFile?.parentFile
    return if (projectRoot != null) {
        ProjectDirStructure.getApkExportRuntimeLibsDir(projectRoot.absolutePath)
    } else {
        File(fallbackRoot, "apk_runtime_libs")
    }
}

internal fun importIconFile(context: Context, uri: Uri, targetDir: File): File {
    targetDir.mkdirs()
    val displayName = queryDisplayName(context, uri)
    val baseName = displayName
        ?.substringBeforeLast('.')
        ?.trim()
        ?.ifBlank { null }
        ?.let(::sanitizeIconFileName)
        ?: "icon-${System.currentTimeMillis()}"
    val extension = displayName?.substringAfterLast('.', missingDelimiterValue = "")
        ?.lowercase()
        ?.takeIf { it.isNotEmpty() && it in SUPPORTED_ICON_EXTENSIONS }
        ?: "png"
    val target = File(targetDir, "$baseName.$extension")

    val inputStream = context.contentResolver.openInputStream(uri)
        ?: throw java.io.IOException("Unable to open selected icon")
    inputStream.use { input ->
        target.outputStream().use { output ->
            input.copyTo(output)
        }
    }
    return target
}

internal fun importRuntimeLibraryFiles(
    context: Context,
    uris: List<Uri>,
    targetDir: File
): List<File> {
    targetDir.mkdirs()
    val imported = linkedMapOf<String, File>()
    uris.forEachIndexed { index, uri ->
        val displayName = queryDisplayName(context, uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "runtime-lib-$index.so"
        if (!isSharedLibraryFileName(displayName)) return@forEachIndexed

        val targetName = sanitizeRuntimeLibraryFileName(displayName)
        if (!isSharedLibraryFileName(targetName)) return@forEachIndexed

        val target = File(targetDir, targetName)
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw java.io.IOException("Unable to open selected runtime library")
        inputStream.use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        imported[target.name] = target
    }
    return imported.values.toList()
}

internal fun queryDisplayName(context: Context, uri: Uri): String? = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
    if (cursor.moveToFirst() && nameIndex >= 0) {
        cursor.getString(nameIndex)
    } else {
        null
    }
}

internal fun sanitizeIconFileName(raw: String): String {
    val cleaned = raw.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_")
    return cleaned.ifBlank { "icon" }
}

internal fun sanitizeRuntimeLibraryFileName(raw: String): String {
    val cleaned = raw.trim().replace(Regex("[^A-Za-z0-9._+\\-]+"), "_")
    return cleaned.ifBlank { "runtime-lib.so" }
}

internal fun isSharedLibraryFileName(name: String): Boolean = name.contains(".so", ignoreCase = true)

internal fun mergeNamedLibraries(
    baseLibraries: List<File>,
    additionalLibraries: List<File>
): List<File> = (baseLibraries + additionalLibraries)
    .asSequence()
    .filter(File::isFile)
    .distinctBy { library ->
        runCatching { library.canonicalPath }.getOrDefault(library.absolutePath)
    }
    .toList()

internal fun deleteManagedApkExportFileIfPresent(file: File, managedDir: File) {
    val resolvedFile = runCatching { file.canonicalFile }.getOrDefault(file.absoluteFile)
    val resolvedDir = runCatching { managedDir.canonicalFile }.getOrDefault(managedDir.absoluteFile)
    if (resolvedFile.parentFile == resolvedDir && resolvedFile.exists()) {
        resolvedFile.delete()
    }
}

private val SUPPORTED_ICON_EXTENSIONS = setOf("png", "webp", "jpg", "jpeg", "bmp")

internal fun resolveSigningProfileFile(outputDir: File, fallbackRoot: File): File {
    val projectRoot = outputDir.parentFile?.parentFile
    return if (projectRoot != null) {
        ProjectDirStructure.getApkSigningPropertiesFile(projectRoot.absolutePath)
    } else {
        File(File(fallbackRoot, "apk-export"), "signing.properties")
    }
}

internal fun loadRememberedCustomSigning(
    outputDir: File,
    fallbackRoot: File
): RememberedCustomSigning? {
    val profileFile = resolveSigningProfileFile(outputDir, fallbackRoot)
    if (!profileFile.exists()) return null

    return runCatching {
        val properties = Properties()
        profileFile.inputStream().use { input ->
            properties.load(input)
        }

        val path = properties.getProperty("keystoreFile")?.trim().orEmpty()
        val keyAlias = properties.getProperty("keyAlias")?.trim().orEmpty()
        if (path.isBlank() || keyAlias.isBlank()) return null

        val keyStoreFile = File(path)
        if (!keyStoreFile.exists()) return null
        RememberedCustomSigning(
            keyStoreFile = keyStoreFile,
            keyAlias = keyAlias
        )
    }.getOrNull()
}

internal fun rememberCustomSigning(
    outputDir: File,
    fallbackRoot: File,
    keyStoreFile: File,
    keyAlias: String
) {
    val profileFile = resolveSigningProfileFile(outputDir, fallbackRoot)
    profileFile.parentFile?.mkdirs()

    runCatching {
        val properties = Properties().apply {
            setProperty("keystoreFile", keyStoreFile.absolutePath)
            setProperty("keyAlias", keyAlias)
        }
        profileFile.outputStream().use { output ->
            properties.store(output, "TinaIDE APK signing profile")
        }
    }
}

internal fun defaultGeneratedKeyStoreFileName(projectName: String): String {
    val baseName = projectName.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "tinaide-signing" }
    return "$baseName-signing.p12"
}

internal fun buildCustomSigningConfig(
    context: Context,
    keyStoreFile: File?,
    storePassword: String,
    keyAlias: String,
    keyPassword: String
): Result<ApkSigningConfig.Custom> = runCatching {
    when {
        keyStoreFile == null -> error(Strings.apk_builder_keystore_required.strOr(context))
        storePassword.isBlank() -> error(Strings.apk_builder_keystore_password_required.strOr(context))
        keyAlias.isBlank() -> error(Strings.apk_builder_key_alias_required.strOr(context))
        keyPassword.isBlank() -> error(Strings.apk_builder_key_password_required.strOr(context))
    }

    val keyStoreInfo = DebugKeyStore.fromFile(
        file = keyStoreFile,
        storePassword = storePassword,
        keyAlias = keyAlias.trim(),
        keyPassword = keyPassword
    )
    validateCustomSigningConfig(context, keyStoreInfo)
    ApkSigningConfig.Custom(keyStoreInfo)
}

internal fun validateCustomSigningConfig(
    context: Context,
    keyStoreInfo: DebugKeyStore.KeyStoreInfo
) {
    val keyStore = try {
        keyStoreInfo.loadKeyStore()
    } catch (error: Exception) {
        throw IllegalArgumentException(
            Strings.apk_builder_keystore_open_failed
                .strOr(context, error.message ?: error.javaClass.simpleName)
        )
    }

    if (!keyStore.containsAlias(keyStoreInfo.keyAlias)) {
        throw IllegalArgumentException(
            Strings.apk_builder_keystore_alias_invalid.strOr(context, keyStoreInfo.keyAlias)
        )
    }

    try {
        val key = keyStore.getKey(keyStoreInfo.keyAlias, keyStoreInfo.keyPassword.toCharArray())
        if (key == null || !keyStore.isKeyEntry(keyStoreInfo.keyAlias)) {
            throw IllegalArgumentException(Strings.apk_builder_key_password_invalid.strOr(context))
        }
    } catch (_: UnrecoverableKeyException) {
        throw IllegalArgumentException(Strings.apk_builder_key_password_invalid.strOr(context))
    }
}

internal fun formatApkBuildError(
    context: Context,
    throwable: Throwable?,
    fallbackMessage: String?
): String {
    val detail = resolveApkBuildErrorDetail(context, throwable, fallbackMessage)
    return if (isSigningFailure(throwable, fallbackMessage)) {
        Strings.apk_builder_sign_failed.strOr(context, detail)
    } else {
        Strings.apk_builder_failed.strOr(context, detail)
    }
}

internal fun resolveApkBuildErrorDetail(
    context: Context,
    throwable: Throwable?,
    fallbackMessage: String?
): String {
    val messages = buildList {
        var current = throwable
        while (current != null) {
            current.message
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let(::add)
            current = current.cause
        }
        fallbackMessage
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(::add)
    }

    return messages.firstOrNull(::isMeaningfulApkBuildMessage)
        ?: messages.firstOrNull()
        ?: (throwable?.javaClass?.simpleName ?: Strings.error_unknown.strOr(context))
}

internal fun isMeaningfulApkBuildMessage(message: String): Boolean {
    val normalized = message.trim()
    val localizedTemplate = Strings.apk_builder_failed.str("")
    val localizedPrefix = localizedTemplate
        .trim()
        .removeSuffix(":")
        .removeSuffix("：")
        .trim()
    return normalized != localizedPrefix &&
        normalized != localizedTemplate.trim()
}

internal fun isSigningFailure(
    throwable: Throwable?,
    fallbackMessage: String?
): Boolean {
    if (throwable is SignatureException) return true

    var current = throwable
    while (current != null) {
        if (current is SignatureException) return true
        current = current.cause
    }

    val message = fallbackMessage.orEmpty()
    return message.contains("Failed to sign", ignoreCase = true) ||
        message.contains("Failed to encode signature block", ignoreCase = true) ||
        message.contains("signer", ignoreCase = true)
}

internal suspend fun installBuiltApk(context: Context, apkFile: File) {
    val shareableFile = ExternalFileIntents.ensureShareableFile(context, apkFile).getOrThrow()
    val apkUri = buildApkUri(context, shareableFile)
    ExternalFileIntents.logFileProviderDiagnostics(context, shareableFile)
    val canRequestInstalls = context.packageManager.canRequestPackageInstalls()

    Timber.tag(APK_PACKAGE_DIALOG_TAG).i(
        "Install requested: source=%s shareable=%s exists=%s size=%d uri=%s canRequestInstalls=%s",
        apkFile.absolutePath,
        shareableFile.absolutePath,
        shareableFile.exists(),
        shareableFile.length(),
        apkUri,
        canRequestInstalls
    )

    if (!canRequestInstalls) {
        Timber.tag(APK_PACKAGE_DIALOG_TAG).w(
            "Missing unknown-app install permission; opening per-app install settings"
        )
        val settingsIntent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            "package:${context.packageName}".toUri()
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(settingsIntent)
        Toast.makeText(
            context,
            Strings.apk_builder_install_permission_required.strOr(context),
            Toast.LENGTH_LONG
        ).show()
        return
    }

    val launchIntent = createApkInstallIntent(context, apkUri, shareableFile.name)
    val resolvedComponent = launchIntent.resolveActivity(context.packageManager)
        ?: throw IllegalStateException(Strings.apk_builder_install_unavailable.strOr(context))

    grantApkUriToInstallHandlers(context, apkUri, launchIntent)
    Timber.tag(APK_PACKAGE_DIALOG_TAG).i(
        "Starting APK installer: action=%s package=%s component=%s uri=%s",
        launchIntent.action,
        launchIntent.`package`,
        resolvedComponent.flattenToShortString(),
        apkUri
    )
    context.startActivity(launchIntent)
}

internal fun createApkInstallIntent(context: Context, apkUri: Uri, apkName: String): Intent {
    val packageManager = context.packageManager
    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(apkUri, "application/vnd.android.package-archive")
        clipData = ClipData.newUri(context.contentResolver, apkName, apkUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    findPreferredPackageInstaller(packageManager, viewIntent)?.let { installerPackage ->
        viewIntent.setPackage(installerPackage)
    }
    return viewIntent
}

internal fun findPreferredPackageInstaller(
    packageManager: PackageManager,
    intent: Intent
): String? {
    val handlers = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
    Timber.tag(APK_PACKAGE_DIALOG_TAG).i(
        "APK install handlers for %s: %s",
        intent.action,
        handlers.joinToString { resolveInfo ->
            "${resolveInfo.activityInfo.packageName}/${resolveInfo.activityInfo.name}"
        }
    )
    return handlers.firstOrNull { it.activityInfo.packageName == SYSTEM_PACKAGE_INSTALLER_PACKAGE }
        ?.activityInfo
        ?.packageName
        ?: handlers.firstOrNull(ResolveInfo::isSystemInstaller)
            ?.activityInfo
            ?.packageName
}

internal fun ResolveInfo.isSystemInstaller(): Boolean {
    val flags = activityInfo.applicationInfo.flags
    return flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
}

internal fun grantApkUriToInstallHandlers(context: Context, apkUri: Uri, intent: Intent) {
    val packageManager = context.packageManager
    val handlers = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
    val packages = buildSet {
        handlers.mapTo(this) { it.activityInfo.packageName }
        intent.`package`?.let(::add)
        intent.resolveActivity(packageManager)?.packageName?.let(::add)
    }
    packages.forEach { packageName ->
        context.grantUriPermission(
            packageName,
            apkUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }
}

internal suspend fun shareBuiltApk(context: Context, apkFile: File) {
    val shareableFile = ExternalFileIntents.ensureShareableFile(context, apkFile).getOrThrow()
    val apkUri = buildApkUri(context, shareableFile)
    ExternalFileIntents.logFileProviderDiagnostics(context, shareableFile)
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "application/vnd.android.package-archive"
        putExtra(Intent.EXTRA_STREAM, apkUri)
        clipData = ClipData.newUri(context.contentResolver, shareableFile.name, apkUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(shareIntent, null))
}

internal fun buildApkUri(context: Context, apkFile: File): Uri = ExternalFileIntents.getShareableUri(context, apkFile)
