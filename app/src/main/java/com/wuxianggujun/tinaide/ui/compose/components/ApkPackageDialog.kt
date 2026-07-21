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

internal const val APK_PACKAGE_DIALOG_TAG = "ApkPackageDialog"
internal const val SYSTEM_PACKAGE_INSTALLER_PACKAGE = "com.android.packageinstaller"
private val PACKAGE_NAME_REGEX = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")

internal enum class ApkSigningMode {
    DEBUG,
    CUSTOM
}

internal data class RememberedCustomSigning(
    val keyStoreFile: File,
    val keyAlias: String
)

/**
 * Dialog for configuring and building an APK from compiled .so files.
 *
 * @param soFiles Pre-compiled shared library files to package
 * @param executableFile Optional terminal executable file to package into assets
 * @param projectName Default app/project name
 * @param templateOptions Available APK template choices
 * @param sdlLibraryPath Optional SDL3 library path
 * @param preloadLibraries Additional libraries to include
 * @param missingLibraries Runtime libraries that could not be auto-resolved
 * @param availablePackages Package index used to infer providers for missing libraries
 * @param installedLibraryPackageIndex Installed package providers indexed by library name
 * @param onOpenPackageManager Called when user wants to search packages for missing libraries
 * @param onDismiss Called when dialog is dismissed
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApkPackageDialog(
    soFiles: List<File>,
    executableFile: File? = null,
    projectName: String,
    outputDir: File,
    templateOptions: List<ApkExportTemplateOption>,
    initialTemplateOptionId: String? = null,
    sdlLibraryPath: File? = null,
    preloadLibraries: List<File> = emptyList(),
    missingLibraries: List<String> = emptyList(),
    availablePackages: List<GUIPackage> = emptyList(),
    installedLibraryPackageIndex: Map<String, String> = emptyMap(),
    onOpenPackageManager: ((String) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apkBuilder = remember { ApkBuilder(context) }
    val managedKeyStoreDir = remember(outputDir, context.filesDir) {
        resolveManagedKeyStoreDir(outputDir, context.filesDir)
    }
    val managedIconDir = remember(outputDir, context.filesDir) {
        resolveManagedIconDir(outputDir, context.filesDir)
    }
    val managedRuntimeLibraryDir = remember(outputDir, context.filesDir) {
        resolveManagedRuntimeLibraryDir(outputDir, context.filesDir)
    }
    val rememberedCustomSigning = remember(outputDir, context.filesDir) {
        loadRememberedCustomSigning(outputDir, context.filesDir)
    }
    val rememberedPermissionProfile = remember(outputDir, context.filesDir) {
        loadRememberedApkExportPermissions(outputDir, context.filesDir)
    }

    var appName by remember { mutableStateOf(projectName) }
    var packageName by remember { mutableStateOf("com.tinaide.user.${projectName.lowercase().replace(Regex("[^a-z0-9]"), "")}") }
    var versionName by remember { mutableStateOf("1.0") }
    var versionCodeText by remember {
        mutableStateOf(
            (rememberedPermissionProfile?.versionCode ?: 1).toString()
        )
    }
    var selectedTemplateOptionId by remember(initialTemplateOptionId, templateOptions) {
        mutableStateOf(initialTemplateOptionId ?: templateOptions.firstOrNull()?.id)
    }
    var templateDropdownExpanded by remember { mutableStateOf(false) }
    var selectedBuiltinPermissions by remember {
        mutableStateOf(
            rememberedPermissionProfile?.selectedBuiltinPermissions ?: defaultApkPermissionSet
        )
    }
    var customIconFile by remember {
        mutableStateOf(rememberedPermissionProfile?.iconFilePath?.let(::File)?.takeIf { it.exists() })
    }
    val selectedAdditionalRuntimeLibraries = remember(
        rememberedPermissionProfile?.additionalRuntimeLibraryPaths
    ) {
        mutableStateListOf<File>().apply {
            addAll(
                rememberedPermissionProfile
                    ?.additionalRuntimeLibraryPaths
                    ?.map(::File)
                    ?.filter(File::isFile)
                    .orEmpty()
            )
        }
    }
    var signingMode by remember {
        mutableStateOf(
            if (rememberedCustomSigning != null) ApkSigningMode.CUSTOM else ApkSigningMode.DEBUG
        )
    }

    var customKeyStoreFile by remember { mutableStateOf(rememberedCustomSigning?.keyStoreFile) }
    var customStorePassword by remember { mutableStateOf("") }
    var customKeyAlias by remember { mutableStateOf(rememberedCustomSigning?.keyAlias ?: "release") }
    var customKeyPassword by remember { mutableStateOf("") }
    var customKeyFingerprints by remember {
        mutableStateOf<DebugKeyStore.CertificateFingerprints?>(null)
    }

    LaunchedEffect(customKeyStoreFile, customStorePassword, customKeyAlias, customKeyPassword, signingMode) {
        val file = customKeyStoreFile
        customKeyFingerprints = if (
            signingMode == ApkSigningMode.CUSTOM &&
            file != null &&
            customStorePassword.isNotBlank() &&
            customKeyAlias.isNotBlank() &&
            customKeyPassword.isNotBlank()
        ) {
            withContext(Dispatchers.IO) {
                DebugKeyStore.computeFingerprints(
                    DebugKeyStore.fromFile(
                        file = file,
                        storePassword = customStorePassword,
                        keyAlias = customKeyAlias.trim(),
                        keyPassword = customKeyPassword
                    )
                )
            }
        } else {
            null
        }
    }

    var showGenerateDialog by remember { mutableStateOf(false) }
    var showPermissionSheet by remember { mutableStateOf(false) }
    var generateFileName by remember { mutableStateOf(defaultGeneratedKeyStoreFileName(projectName)) }
    var generateCommonName by remember { mutableStateOf(projectName) }
    var generateStorePassword by remember { mutableStateOf("") }
    var generateKeyAlias by remember { mutableStateOf("release") }
    var generateKeyPassword by remember { mutableStateOf("") }
    var generateError by remember { mutableStateOf<String?>(null) }

    var isPreparingKeyStore by remember { mutableStateOf(false) }
    var isBuilding by remember { mutableStateOf(false) }
    var buildProgress by remember { mutableFloatStateOf(0f) }
    var buildMessage by remember { mutableStateOf("") }
    var builtApkFile by remember { mutableStateOf<File?>(null) }
    var buildError by remember { mutableStateOf<String?>(null) }

    val effectiveSoFiles = mergeNamedLibraries(soFiles, selectedAdditionalRuntimeLibraries)
    val remainingMissingLibraries = NativeLibraryDependencyHints.filterUnresolvedLibraries(
        missingLibraries = missingLibraries,
        providedLibraries = effectiveSoFiles
    )
    val suggestedMissingLibraryPackageIds = remember(
        remainingMissingLibraries,
        availablePackages,
        installedLibraryPackageIndex,
        context.filesDir
    ) {
        NativeLibraryDependencyHints.inferPackageIds(
            libraryNames = remainingMissingLibraries,
            availablePackages = availablePackages,
            installedLibraryPackageIndex = installedLibraryPackageIndex,
        )
    }
    val missingLibrariesMessage = if (remainingMissingLibraries.isNotEmpty()) {
        NativeLibraryDependencyHints.buildMissingLibrariesMessage(
            context = context,
            missingLibraries = remainingMissingLibraries,
            includeApkImportHint = true,
            suggestedPackageIds = suggestedMissingLibraryPackageIds
        )
    } else {
        null
    }
    val hasNativeEntryLibrary = effectiveSoFiles.any { it.name == "libmain.so" }
    val selectedTemplateOption = templateOptions.firstOrNull {
        it.id == selectedTemplateOptionId
    } ?: templateOptions.firstOrNull()
    val selectedTemplateType = selectedTemplateOption?.templateType
    val isTemplateSelectionEnabled = templateOptions.size > 1

    LaunchedEffect(templateOptions, selectedTemplateOptionId) {
        if (templateOptions.isEmpty()) {
            templateDropdownExpanded = false
            return@LaunchedEffect
        }
        if (selectedTemplateOption == null) {
            selectedTemplateOptionId = templateOptions.first().id
            templateDropdownExpanded = false
        }
    }

    val parsedVersionCode = versionCodeText.trim().toIntOrNull()?.takeIf { it > 0 }

    val trimmedPackageName = packageName.trim()
    val isPackageNameValid = PACKAGE_NAME_REGEX.matches(trimmedPackageName)
    val showPackageNameError = packageName.isNotBlank() && !isPackageNameValid
    val hasArtifactsForSelectedTemplate = when (selectedTemplateType) {
        ApkTemplateType.NATIVE_ACTIVITY,
        ApkTemplateType.SDL3 -> hasNativeEntryLibrary
        ApkTemplateType.TERMINAL -> executableFile != null
        null -> false
    }
    val artifactErrorMessage = when (selectedTemplateType) {
        ApkTemplateType.NATIVE_ACTIVITY,
        ApkTemplateType.SDL3 -> {
            if (!hasNativeEntryLibrary) stringResource(Strings.apk_builder_no_entry_library) else null
        }

        ApkTemplateType.TERMINAL -> {
            if (executableFile == null) stringResource(Strings.apk_builder_no_executable_file) else null
        }

        null -> null
    }

    val canBuild = selectedTemplateOption != null &&
        hasArtifactsForSelectedTemplate &&
        appName.isNotBlank() &&
        trimmedPackageName.isNotBlank() &&
        isPackageNameValid &&
        parsedVersionCode != null &&
        !isPreparingKeyStore &&
        when (signingMode) {
            ApkSigningMode.DEBUG -> true
            ApkSigningMode.CUSTOM ->
                customKeyStoreFile != null &&
                    customStorePassword.isNotBlank() &&
                    customKeyAlias.isNotBlank() &&
                    customKeyPassword.isNotBlank()
        }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isPreparingKeyStore = true
            runCatching {
                ApkKeyStoreManager.importKeyStore(
                    context = context,
                    uri = uri,
                    targetDir = managedKeyStoreDir
                )
            }.onSuccess { file ->
                customKeyStoreFile = file
                signingMode = ApkSigningMode.CUSTOM
                rememberCustomSigning(
                    outputDir = outputDir,
                    fallbackRoot = context.filesDir,
                    keyStoreFile = file,
                    keyAlias = customKeyAlias.trim().ifBlank { "release" }
                )
                buildError = null
                Toast.makeText(
                    context,
                    Strings.apk_builder_keystore_import_success.strOr(context, file.name),
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure { error ->
                buildError = Strings.apk_builder_keystore_import_failed
                    .strOr(context, error.message ?: error.javaClass.simpleName)
            }
            isPreparingKeyStore = false
        }
    }

    val iconLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    importIconFile(context, uri, managedIconDir)
                }
            }.onSuccess { file ->
                customIconFile = file
                buildError = null
            }.onFailure { error ->
                buildError = Strings.apk_builder_icon_import_failed
                    .strOr(context, error.message ?: error.javaClass.simpleName)
            }
        }
    }

    val runtimeLibraryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    importRuntimeLibraryFiles(
                        context = context,
                        uris = uris,
                        targetDir = managedRuntimeLibraryDir
                    )
                }
            }.onSuccess { importedFiles ->
                if (importedFiles.isEmpty()) {
                    buildError = Strings.apk_builder_runtime_libraries_invalid_selection.strOr(context)
                } else {
                    importedFiles.forEach { imported ->
                        selectedAdditionalRuntimeLibraries.removeAll { it.name == imported.name }
                        selectedAdditionalRuntimeLibraries.add(imported)
                    }
                    buildError = null
                }
            }.onFailure { error ->
                buildError = Strings.apk_builder_runtime_libraries_import_failed
                    .strOr(context, error.message ?: error.javaClass.simpleName)
            }
        }
    }

    if (showGenerateDialog) {
        TinaAlertDialog(
            onDismissRequest = { if (!isPreparingKeyStore) showGenerateDialog = false },
            title = {
                TinaDialogTitleText(stringResource(Strings.apk_builder_generate_keystore_title))
            },
            text = {
                TinaDialogContentColumn(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    TinaDialogCard(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.14f)
                    ) {
                        generateError?.let { message ->
                            Text(
                                text = message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        OutlinedTextField(
                            value = generateFileName,
                            onValueChange = {
                                generateFileName = it
                                generateError = null
                            },
                            label = { Text(stringResource(Strings.apk_builder_keystore_file_name)) },
                            placeholder = { Text(stringResource(Strings.apk_builder_keystore_file_name_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = generateCommonName,
                            onValueChange = {
                                generateCommonName = it
                                generateError = null
                            },
                            label = { Text(stringResource(Strings.apk_builder_keystore_common_name)) },
                            placeholder = { Text(stringResource(Strings.apk_builder_keystore_common_name_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = generateKeyAlias,
                            onValueChange = {
                                generateKeyAlias = it
                                generateError = null
                            },
                            label = { Text(stringResource(Strings.apk_builder_key_alias)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = generateStorePassword,
                            onValueChange = {
                                generateStorePassword = it
                                generateError = null
                            },
                            label = { Text(stringResource(Strings.apk_builder_keystore_password)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = generateKeyPassword,
                            onValueChange = {
                                generateKeyPassword = it
                                generateError = null
                            },
                            label = { Text(stringResource(Strings.apk_builder_key_password)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                TinaPrimaryButton(
                    text = stringResource(Strings.apk_builder_generate_keystore),
                    onClick = {
                        val validationError = when {
                            generateFileName.isBlank() -> Strings.apk_builder_keystore_file_name_required.strOr(context)
                            generateCommonName.isBlank() -> Strings.apk_builder_keystore_common_name_required.strOr(context)
                            generateStorePassword.isBlank() -> Strings.apk_builder_keystore_password_required.strOr(context)
                            generateKeyAlias.isBlank() -> Strings.apk_builder_key_alias_required.strOr(context)
                            generateKeyPassword.isBlank() -> Strings.apk_builder_key_password_required.strOr(context)
                            else -> null
                        }
                        if (validationError != null) {
                            generateError = validationError
                        } else {
                            scope.launch {
                                isPreparingKeyStore = true
                                runCatching {
                                    ApkKeyStoreManager.generateKeyStore(
                                        targetDir = managedKeyStoreDir,
                                        params = ApkKeyStoreManager.GenerateParams(
                                            fileName = generateFileName.trim(),
                                            storePassword = generateStorePassword,
                                            keyAlias = generateKeyAlias.trim(),
                                            keyPassword = generateKeyPassword,
                                            commonName = generateCommonName.trim()
                                        )
                                    )
                                }.onSuccess { keyStoreInfo ->
                                    customKeyStoreFile = keyStoreInfo.file
                                    customStorePassword = keyStoreInfo.storePassword
                                    customKeyAlias = keyStoreInfo.keyAlias
                                    customKeyPassword = keyStoreInfo.keyPassword
                                    signingMode = ApkSigningMode.CUSTOM
                                    rememberCustomSigning(
                                        outputDir = outputDir,
                                        fallbackRoot = context.filesDir,
                                        keyStoreFile = keyStoreInfo.file,
                                        keyAlias = keyStoreInfo.keyAlias
                                    )
                                    buildError = null
                                    generateError = null
                                    showGenerateDialog = false
                                    Toast.makeText(
                                        context,
                                        Strings.apk_builder_keystore_generate_success.strOr(context, keyStoreInfo.file.name),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }.onFailure { error ->
                                    generateError = Strings.apk_builder_keystore_generate_failed
                                        .strOr(context, error.message ?: error.javaClass.simpleName)
                                }
                                isPreparingKeyStore = false
                            }
                        }
                    },
                    enabled = !isPreparingKeyStore
                )
            },
            dismissButton = {
                TinaTextButton(
                    text = stringResource(Strings.btn_cancel),
                    onClick = { showGenerateDialog = false },
                    enabled = !isPreparingKeyStore
                )
            }
        )
    }

    TinaAlertDialog(
        onDismissRequest = { if (!isBuilding && !isPreparingKeyStore) onDismiss() },
        title = {
            TinaDialogTitleText(stringResource(Strings.apk_builder_title))
        },
        text = {
            TinaDialogContentColumn(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (builtApkFile != null) {
                    // Success state
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(48.dp)
                            .align(Alignment.CenterHorizontally)
                    )
                    Text(
                        Strings.apk_builder_success.strOr(context),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                    Text(
                        builtApkFile!!.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                } else if (isBuilding) {
                    // Building state
                    LinearProgressIndicator(
                        progress = { buildProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        buildMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    if (buildError != null) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Text(
                                buildError!!,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }

                    OutlinedTextField(
                        value = appName,
                        onValueChange = { appName = it },
                        label = { Text(stringResource(Strings.apk_builder_app_name)) },
                        placeholder = { Text(stringResource(Strings.apk_builder_name_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = packageName,
                        onValueChange = { packageName = it },
                        label = { Text(stringResource(Strings.apk_builder_package_name)) },
                        placeholder = { Text(stringResource(Strings.apk_builder_package_hint)) },
                        isError = showPackageNameError,
                        supportingText = if (showPackageNameError) {
                            { Text(stringResource(Strings.apk_builder_package_invalid)) }
                        } else {
                            null
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = versionName,
                        onValueChange = { versionName = it },
                        label = { Text(stringResource(Strings.apk_builder_version_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = versionCodeText,
                        onValueChange = { input ->
                            versionCodeText = input.filter { it.isDigit() }.take(9)
                        },
                        label = { Text(stringResource(Strings.apk_builder_version_code)) },
                        supportingText = {
                            Text(stringResource(Strings.apk_builder_version_code_hint))
                        },
                        isError = versionCodeText.isNotBlank() && parsedVersionCode == null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = stringResource(Strings.apk_builder_permissions_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = stringResource(Strings.apk_builder_permissions_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val highRiskSelectedCount = selectedBuiltinPermissions.count { perm ->
                        apkPermissionOptions.any { it.permission == perm && it.isHighRisk }
                    }
                    val permissionSummary = when {
                        selectedBuiltinPermissions.isEmpty() ->
                            stringResource(Strings.apk_builder_permissions_summary_none)
                        highRiskSelectedCount > 0 ->
                            stringResource(
                                Strings.apk_builder_permissions_summary_with_risk,
                                selectedBuiltinPermissions.size,
                                highRiskSelectedCount
                            )
                        else ->
                            stringResource(
                                Strings.apk_builder_permissions_summary_count,
                                selectedBuiltinPermissions.size
                            )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = permissionSummary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TinaOutlinedButton(
                            text = stringResource(Strings.apk_builder_permissions_manage),
                            onClick = { showPermissionSheet = true }
                        )
                    }

                    val hasHighRiskSelected = hasHighRiskPermissionSelected(
                        selectedBuiltinPermissions = selectedBuiltinPermissions
                    )
                    if (hasHighRiskSelected) {
                        TinaDialogCard(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.18f),
                            border = BorderStroke(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
                            )
                        ) {
                            Text(
                                text = stringResource(Strings.apk_builder_permission_high_risk_warning_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = stringResource(Strings.apk_builder_permission_high_risk_warning_message),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Text(
                        text = stringResource(Strings.apk_builder_icon_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = stringResource(Strings.apk_builder_icon_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = customIconFile?.name
                                ?: stringResource(Strings.apk_builder_icon_none),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TinaOutlinedButton(
                            text = stringResource(
                                if (customIconFile == null) {
                                    Strings.apk_builder_icon_pick
                                } else {
                                    Strings.apk_builder_icon_replace
                                }
                            ),
                            onClick = { iconLauncher.launch("image/*") }
                        )
                        if (customIconFile != null) {
                            TinaTextButton(
                                text = stringResource(Strings.apk_builder_icon_clear),
                                onClick = { customIconFile = null }
                            )
                        }
                    }

                    Text(
                        text = stringResource(Strings.apk_builder_runtime_libraries_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = stringResource(Strings.apk_builder_runtime_libraries_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (selectedAdditionalRuntimeLibraries.isEmpty()) {
                                stringResource(Strings.apk_builder_runtime_libraries_none)
                            } else {
                                stringResource(
                                    Strings.apk_builder_runtime_libraries_count,
                                    selectedAdditionalRuntimeLibraries.size
                                )
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TinaOutlinedButton(
                            text = stringResource(Strings.apk_builder_runtime_libraries_import),
                            onClick = { runtimeLibraryLauncher.launch("*/*") }
                        )
                        if (selectedAdditionalRuntimeLibraries.isNotEmpty()) {
                            TinaTextButton(
                                text = stringResource(Strings.apk_builder_runtime_libraries_clear),
                                onClick = {
                                    selectedAdditionalRuntimeLibraries
                                        .toList()
                                        .forEach { deleteManagedApkExportFileIfPresent(it, managedRuntimeLibraryDir) }
                                    selectedAdditionalRuntimeLibraries.clear()
                                }
                            )
                        }
                    }
                    if (selectedAdditionalRuntimeLibraries.isNotEmpty()) {
                        TinaDialogCard(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.14f)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                selectedAdditionalRuntimeLibraries.forEach { library ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = library.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TinaTextButton(
                                            text = stringResource(Strings.apk_builder_runtime_libraries_remove),
                                            onClick = {
                                                selectedAdditionalRuntimeLibraries.remove(library)
                                                deleteManagedApkExportFileIfPresent(
                                                    library,
                                                    managedRuntimeLibraryDir
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (missingLibrariesMessage != null) {
                        TinaDialogCard(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.18f),
                            border = BorderStroke(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
                            )
                        ) {
                            Text(
                                text = stringResource(Strings.native_library_missing_libraries_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = missingLibrariesMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (
                                suggestedMissingLibraryPackageIds.isNotEmpty() &&
                                onOpenPackageManager != null
                            ) {
                                TinaTextButton(
                                    text = stringResource(Strings.native_library_open_package_manager),
                                    onClick = { onOpenPackageManager(suggestedMissingLibraryPackageIds.first()) }
                                )
                            }
                        }
                    }

                    ExposedDropdownMenuBox(
                        expanded = templateDropdownExpanded,
                        onExpandedChange = { expanded ->
                            if (isTemplateSelectionEnabled) {
                                templateDropdownExpanded = expanded
                            }
                        }
                    ) {
                        OutlinedTextField(
                            value = selectedTemplateOption?.label.orEmpty(),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(Strings.apk_builder_template_type)) },
                            trailingIcon = {
                                if (isTemplateSelectionEnabled) {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = templateDropdownExpanded)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(
                                    type = ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                                    enabled = isTemplateSelectionEnabled
                                )
                        )
                        TinaExposedDropdownMenu(
                            expanded = templateDropdownExpanded && isTemplateSelectionEnabled,
                            onDismissRequest = { templateDropdownExpanded = false }
                        ) {
                            templateOptions.forEach { option ->
                                TinaDropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = {
                                        selectedTemplateOptionId = option.id
                                        templateDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Text(
                        text = stringResource(Strings.apk_builder_signing_mode),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = signingMode == ApkSigningMode.DEBUG,
                                onClick = { signingMode = ApkSigningMode.DEBUG }
                            )
                            Column {
                                Text(stringResource(Strings.apk_builder_signing_debug))
                                Text(
                                    text = stringResource(Strings.apk_builder_signing_debug_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = signingMode == ApkSigningMode.CUSTOM,
                                onClick = { signingMode = ApkSigningMode.CUSTOM }
                            )
                            Column {
                                Text(stringResource(Strings.apk_builder_signing_custom))
                                Text(
                                    text = stringResource(Strings.apk_builder_signing_custom_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (signingMode == ApkSigningMode.CUSTOM) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TinaOutlinedButton(
                                text = stringResource(Strings.apk_builder_import_keystore),
                                onClick = { importLauncher.launch("*/*") },
                                enabled = !isPreparingKeyStore
                            )
                            TinaOutlinedButton(
                                text = stringResource(Strings.apk_builder_generate_keystore),
                                onClick = {
                                    generateError = null
                                    showGenerateDialog = true
                                },
                                enabled = !isPreparingKeyStore
                            )
                        }

                        OutlinedTextField(
                            value = customKeyStoreFile?.name.orEmpty(),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(Strings.apk_builder_keystore_file)) },
                            placeholder = { Text(stringResource(Strings.apk_builder_keystore_none)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = customKeyAlias,
                            onValueChange = { customKeyAlias = it },
                            label = { Text(stringResource(Strings.apk_builder_key_alias)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = customStorePassword,
                            onValueChange = { customStorePassword = it },
                            label = { Text(stringResource(Strings.apk_builder_keystore_password)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = customKeyPassword,
                            onValueChange = { customKeyPassword = it },
                            label = { Text(stringResource(Strings.apk_builder_key_password)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )

                        customKeyFingerprints?.let { fp ->
                            TinaDialogCard(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
                            ) {
                                Text(
                                    text = stringResource(Strings.apk_builder_keystore_fingerprints_title),
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = stringResource(Strings.apk_builder_keystore_fingerprints_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                SelectionContainer {
                                    Column {
                                        Text(
                                            text = "SHA-1: ${fp.sha1}",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Text(
                                            text = "SHA-256: ${fp.sha256}",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (artifactErrorMessage != null) {
                        Text(
                            artifactErrorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            when {
                builtApkFile != null -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TinaOutlinedButton(
                            text = stringResource(Strings.apk_builder_install),
                            onClick = {
                                builtApkFile?.let { apk ->
                                    scope.launch {
                                        runCatching { installBuiltApk(context, apk) }
                                            .onFailure { error ->
                                                Toast.makeText(
                                                    context,
                                                    Strings.apk_builder_install_failed.strOr(
                                                        context,
                                                        error.message ?: error.javaClass.simpleName
                                                    ),
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                    }
                                }
                            }
                        )
                        TinaOutlinedButton(
                            text = stringResource(Strings.apk_builder_share),
                            onClick = {
                                builtApkFile?.let { apk ->
                                    scope.launch {
                                        runCatching { shareBuiltApk(context, apk) }
                                            .onFailure { error ->
                                                Toast.makeText(
                                                    context,
                                                    Strings.apk_builder_share_failed.strOr(
                                                        context,
                                                        error.message ?: error.javaClass.simpleName
                                                    ),
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                    }
                                }
                            },
                            leadingIcon = Icons.Default.Share
                        )
                        TinaTextButton(
                            text = stringResource(Strings.action_close),
                            onClick = onDismiss
                        )
                    }
                }

                isBuilding -> {
                    // No actions while building
                }

                else -> {
                    TinaPrimaryButton(
                        text = stringResource(Strings.apk_builder_build),
                        onClick = {
                            val signingConfig = when (signingMode) {
                                ApkSigningMode.DEBUG -> ApkSigningConfig.Debug
                                ApkSigningMode.CUSTOM -> {
                                    val result = buildCustomSigningConfig(
                                        context = context,
                                        keyStoreFile = customKeyStoreFile,
                                        storePassword = customStorePassword,
                                        keyAlias = customKeyAlias,
                                        keyPassword = customKeyPassword
                                    )
                                    val resolvedConfig = result.getOrNull()
                                    if (resolvedConfig == null) {
                                        buildError = result.exceptionOrNull()?.message
                                    }
                                    resolvedConfig
                                }
                            }
                            if (signingConfig != null) {
                                if (signingConfig is ApkSigningConfig.Custom) {
                                    rememberCustomSigning(
                                        outputDir = outputDir,
                                        fallbackRoot = context.filesDir,
                                        keyStoreFile = signingConfig.keyStoreInfo.file,
                                        keyAlias = signingConfig.keyStoreInfo.keyAlias
                                    )
                                }

                                isBuilding = true
                                buildProgress = 0f
                                buildMessage = Strings.apk_builder_building.strOr(context)
                                buildError = null
                                val effectiveVersionCode = parsedVersionCode ?: 1
                                val requestedPermissions = buildRequestedPermissions(
                                    selectedBuiltinPermissions = selectedBuiltinPermissions
                                )
                                val effectiveIconFile = customIconFile?.takeIf { it.exists() }
                                rememberApkExportPermissions(
                                    outputDir = outputDir,
                                    fallbackRoot = context.filesDir,
                                    selectedBuiltinPermissions = selectedBuiltinPermissions,
                                    versionCode = effectiveVersionCode,
                                    iconFilePath = effectiveIconFile?.absolutePath,
                                    additionalRuntimeLibraryPaths = selectedAdditionalRuntimeLibraries
                                        .asSequence()
                                        .filter(File::isFile)
                                        .map(File::getAbsolutePath)
                                        .toList()
                                )

                                val resolvedTemplateOption = selectedTemplateOption
                                if (resolvedTemplateOption == null) {
                                    isBuilding = false
                                    buildError = Strings.apk_builder_template_unavailable.strOr(context)
                                    return@TinaPrimaryButton
                                }
                                val config = ApkBuildConfig(
                                    soFiles = effectiveSoFiles,
                                    targetAbis = listOf(
                                        effectiveSoFiles
                                            .firstOrNull { it.name == "libmain.so" }
                                            ?.let { mainLibrary ->
                                                runCatching { NativeLibraryAbiDetector.detect(mainLibrary) }.getOrNull()
                                            }
                                            ?: Build.SUPPORTED_ABIS.firstOrNull()
                                            ?: "arm64-v8a"
                                    ),
                                    executableFile = if (resolvedTemplateOption.templateType == ApkTemplateType.TERMINAL) executableFile else null,
                                    packageName = packageName.trim(),
                                    appName = appName.trim(),
                                    versionCode = effectiveVersionCode,
                                    versionName = versionName.trim(),
                                    requestedPermissions = requestedPermissions,
                                    templateType = resolvedTemplateOption.templateType,
                                    templateFile = resolvedTemplateOption.templateFile,
                                    sdlLibraryPath = if (resolvedTemplateOption.templateType == ApkTemplateType.SDL3) sdlLibraryPath else null,
                                    preloadLibraries = preloadLibraries,
                                    iconFile = effectiveIconFile,
                                    signingConfig = signingConfig
                                )
                                val outputFile = File(outputDir, "${appName.trim().replace(" ", "_")}.apk")

                                scope.launch {
                                    val result = apkBuilder.build(config, outputFile) { progress ->
                                        when (progress) {
                                            is ApkBuilder.BuildProgress.Step -> {
                                                buildProgress = progress.progress
                                                buildMessage = progress.message
                                            }
                                            is ApkBuilder.BuildProgress.Success -> {
                                                builtApkFile = progress.apkFile
                                                isBuilding = false
                                            }
                                            is ApkBuilder.BuildProgress.Error -> {
                                                buildError = formatApkBuildError(
                                                    context = context,
                                                    throwable = progress.cause,
                                                    fallbackMessage = progress.message
                                                )
                                                isBuilding = false
                                            }
                                        }
                                    }
                                    if (result.isFailure && builtApkFile == null) {
                                        buildError = formatApkBuildError(
                                            context = context,
                                            throwable = result.exceptionOrNull(),
                                            fallbackMessage = result.exceptionOrNull()?.message
                                        )
                                        isBuilding = false
                                    }
                                }
                            }
                        },
                        enabled = canBuild
                    )
                }
            }
        },
        dismissButton = {
            if (!isBuilding && builtApkFile == null) {
                TinaTextButton(
                    text = stringResource(Strings.action_close),
                    onClick = onDismiss,
                    enabled = !isPreparingKeyStore
                )
            }
        }
    )

    if (showPermissionSheet) {
        ApkPermissionPickerSheet(
            selected = selectedBuiltinPermissions,
            onSelectedChange = { selectedBuiltinPermissions = it },
            onDismiss = { showPermissionSheet = false }
        )
    }
}
