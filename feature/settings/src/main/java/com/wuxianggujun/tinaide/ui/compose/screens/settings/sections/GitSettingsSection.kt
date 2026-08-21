
package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.git.AndroidGitCredentialManager
import com.wuxianggujun.tinaide.core.git.GitCredential
import com.wuxianggujun.tinaide.core.git.ssh.GitSshHostBinding
import com.wuxianggujun.tinaide.core.git.ssh.GitSshKeyMeta
import com.wuxianggujun.tinaide.core.git.ssh.GitSshManager
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.network.registry.GitHubRegistryProxyConfig
import com.wuxianggujun.tinaide.ui.compose.components.TinaAlertDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaDangerButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogContentColumn
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogMessageCard
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogTitleText
import com.wuxianggujun.tinaide.ui.compose.components.TinaPrimaryButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaSingleChoiceDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaTextButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaTextField
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsCard
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsCategoryTitle
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsClickableItem
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsSwitchItem
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 整合后的 Git 设置页面，使用 Tab 切换 HTTPS 认证和 SSH 密钥管理
 */
@Composable
internal fun GitSettingsSection() {
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        stringResource(Strings.git_settings_tab_https),
        stringResource(Strings.git_settings_tab_ssh),
        stringResource(Strings.git_settings_tab_github),
    )

    Column(modifier = Modifier.fillMaxSize()) {
        // Tab 固定在顶部，背景颜色与页面一致
        SecondaryTabRow(
            selectedTabIndex = selectedTabIndex,
            modifier = Modifier.fillMaxWidth(),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = {
                        Text(
                            text = title,
                            color = if (selectedTabIndex == index) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                )
            }
        }

        // 内容区域单独滚动
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp)
        ) {
            when (selectedTabIndex) {
                0 -> GitHttpsContent()
                1 -> GitSshContent()
                2 -> GitHubRegistryContent()
            }
        }
    }
}

// ==================== HTTPS 认证部分 ====================

@Composable
private fun GitHttpsContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { AndroidGitCredentialManager(context) }

    val authErrorHostInvalid = stringResource(Strings.git_auth_error_host_invalid)
    val authErrorTokenEmpty = stringResource(Strings.git_auth_error_token_empty)
    val authErrorSaveFailed = stringResource(Strings.git_auth_error_save_failed)
    val authErrorDeleteFailed = stringResource(Strings.git_auth_error_delete_failed)

    var credentials by remember { mutableStateOf<List<GitCredential>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var editorVisible by remember { mutableStateOf(false) }
    var editorHost by remember { mutableStateOf("") }
    var editorUsername by remember { mutableStateOf("") }
    var editorToken by remember { mutableStateOf("") }
    var editorIsEdit by remember { mutableStateOf(false) }
    var editorError by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            isLoading = true
            error = null
            try {
                val hosts = credentialManager.listHttpsHosts()
                val loaded = hosts.mapNotNull { host -> credentialManager.getHttpsCredential(host) }
                credentials = GitSettingsSectionSupport.sortHttpsCredentials(loaded)
            } catch (e: Exception) {
                error = e.message ?: Strings.git_error_load_failed.strOr(context)
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        refresh()
    }

    Spacer(modifier = Modifier.height(8.dp))

    SettingsCategoryTitle(stringResource(Strings.settings_cat_git_auth))

    SettingsCard {
        SettingsClickableItem(
            title = stringResource(Strings.git_auth_add_credential),
            subtitle = stringResource(Strings.git_auth_add_credential_desc),
            value = if (isLoading) stringResource(Strings.git_auth_loading) else null,
            onClick = {
                val editorState = GitSettingsSectionSupport.createAddHttpsEditorState()
                editorIsEdit = editorState.isEdit
                editorHost = editorState.host
                editorUsername = editorState.username
                editorToken = editorState.token
                editorError = null
                editorVisible = true
            },
            showDivider = false
        )
    }

    SettingsCard {
        if (error != null) {
            SettingsClickableItem(
                title = stringResource(Strings.git_auth_error_load_failed_title),
                subtitle = error,
                value = null,
                onClick = { refresh() },
                showDivider = credentials.isNotEmpty()
            )
        }

        if (credentials.isEmpty() && !isLoading && error == null) {
            SettingsClickableItem(
                title = stringResource(Strings.git_auth_empty_title),
                subtitle = stringResource(Strings.git_auth_empty_desc),
                value = null,
                onClick = {
                    val editorState = GitSettingsSectionSupport.createAddHttpsEditorState()
                    editorIsEdit = editorState.isEdit
                    editorHost = editorState.host
                    editorUsername = editorState.username
                    editorToken = editorState.token
                    editorError = null
                    editorVisible = true
                },
                showDivider = false
            )
        } else {
            credentials.forEachIndexed { index, credential ->
                val showDivider = index != credentials.lastIndex
                SettingsClickableItem(
                    title = credential.host,
                    subtitle = stringResource(Strings.git_auth_item_subtitle, credential.username),
                    value = stringResource(Strings.git_auth_item_value_configured),
                    onClick = {
                        val editorState = GitSettingsSectionSupport.createEditHttpsEditorState(
                            credential
                        )
                        editorIsEdit = editorState.isEdit
                        editorHost = editorState.host
                        editorUsername = editorState.username
                        editorToken = editorState.token
                        editorError = null
                        editorVisible = true
                    },
                    showDivider = showDivider
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    if (editorVisible) {
        TinaAlertDialog(
            onDismissRequest = { editorVisible = false },
            title = {
                TinaDialogTitleText(
                    if (editorIsEdit) {
                        stringResource(Strings.git_auth_edit_title)
                    } else {
                        stringResource(Strings.git_auth_add_title)
                    }
                )
            },
            text = {
                val tokenLabel = if (editorIsEdit) {
                    stringResource(Strings.git_auth_token_label_edit)
                } else {
                    stringResource(Strings.git_auth_token_label)
                }

                TinaDialogContentColumn {
                    TinaTextField(
                        value = editorHost,
                        onValueChange = {
                            editorHost = it
                            editorError = null
                        },
                        label = stringResource(Strings.git_auth_host_label),
                        placeholder = stringResource(Strings.git_auth_host_placeholder),
                        enabled = !editorIsEdit
                    )
                    TinaTextField(
                        value = editorUsername,
                        onValueChange = {
                            editorUsername = it
                            editorError = null
                        },
                        label = stringResource(Strings.git_auth_username_label),
                        placeholder = stringResource(Strings.git_auth_username_placeholder)
                    )
                    TinaTextField(
                        value = editorToken,
                        onValueChange = {
                            editorToken = it
                            editorError = null
                        },
                        label = tokenLabel,
                        placeholder = stringResource(Strings.git_auth_token_placeholder),
                        visualTransformation = PasswordVisualTransformation()
                    )
                    editorError?.let { msg ->
                        TinaDialogMessageCard(
                            message = msg,
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f),
                            textColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            },
            confirmButton = {
                TinaPrimaryButton(
                    text = stringResource(Strings.btn_ok),
                    onClick = {
                        val draft = GitSettingsSectionSupport.resolveHttpsCredentialDraft(
                            rawHost = editorHost,
                            rawUsername = editorUsername,
                            rawToken = editorToken,
                        )

                        if (GitSettingsSectionSupport.isHttpsHostInvalid(draft.resolvedHost)) {
                            editorError = authErrorHostInvalid
                            return@TinaPrimaryButton
                        }
                        if (GitSettingsSectionSupport.isNewHttpsTokenMissing(editorIsEdit, draft.token)) {
                            editorError = authErrorTokenEmpty
                            return@TinaPrimaryButton
                        }

                        scope.launch {
                            editorError = null
                            try {
                                if (!editorIsEdit) {
                                    credentialManager.saveHttpsCredential(
                                        draft.resolvedHost,
                                        draft.username,
                                        draft.token
                                    )
                                } else {
                                    val existing = credentialManager.getHttpsCredential(
                                        draft.resolvedHost
                                    )
                                    val finalToken = GitSettingsSectionSupport.resolveHttpsCredentialToken(
                                        inputToken = draft.token,
                                        existingToken = existing?.password,
                                    )
                                    if (finalToken == null) {
                                        editorError = authErrorTokenEmpty
                                        return@launch
                                    }
                                    credentialManager.saveHttpsCredential(
                                        draft.resolvedHost,
                                        draft.username,
                                        finalToken
                                    )
                                }
                                editorVisible = false
                                refresh()
                            } catch (e: Exception) {
                                editorError = e.message ?: authErrorSaveFailed
                            }
                        }
                    }
                )
            },
            dismissButton = {
                if (editorIsEdit) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TinaDangerButton(
                            text = stringResource(Strings.btn_delete),
                            onClick = {
                                val resolvedHost = GitSettingsSectionSupport.extractHost(editorHost)
                                scope.launch {
                                    try {
                                        credentialManager.deleteHttpsCredential(resolvedHost)
                                        editorVisible = false
                                        refresh()
                                    } catch (e: Exception) {
                                        editorError = e.message ?: authErrorDeleteFailed
                                    }
                                }
                            }
                        )
                        TinaTextButton(
                            text = stringResource(Strings.btn_cancel),
                            onClick = { editorVisible = false }
                        )
                    }
                } else {
                    TinaTextButton(
                        text = stringResource(Strings.btn_cancel),
                        onClick = { editorVisible = false }
                    )
                }
            }
        )
    }
}

// ==================== SSH 密钥部分 ====================

@Composable
private fun GitSshContent() {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val sshManager = remember(appContext) { GitSshManager(appContext) }

    val sshErrorLoadFailed = stringResource(Strings.git_ssh_error_load_failed)
    val sshErrorImportFailed = stringResource(Strings.git_ssh_error_import_failed)
    val sshGenerateOkTemplate = stringResource(Strings.git_ssh_generate_ok)
    val sshGenerateFailed = stringResource(Strings.git_ssh_generate_failed)
    val sshKeyNameInvalid = stringResource(Strings.git_ssh_key_name_invalid)
    val sshImportOkTemplate = stringResource(Strings.git_ssh_import_ok)
    val sshPubkeyMissing = stringResource(Strings.git_ssh_pubkey_missing)
    val sshPubkeyCopied = stringResource(Strings.git_ssh_pubkey_copied)
    val sshDeleteOkTemplate = stringResource(Strings.git_ssh_delete_ok)
    val sshDeleteFailed = stringResource(Strings.git_ssh_delete_failed)
    val sshBindingSaveOk = stringResource(Strings.git_ssh_binding_save_ok)
    val sshBindingSaveFailed = stringResource(Strings.git_ssh_binding_save_failed)
    val sshBindingDeleteOk = stringResource(Strings.git_ssh_binding_delete_ok)
    val sshBindingHostInvalid = stringResource(Strings.git_ssh_binding_host_invalid)
    val sshBindingPortInvalid = stringResource(Strings.git_ssh_binding_port_invalid)
    val sshTrustedHostsResetOk = stringResource(Strings.git_ssh_trusted_hosts_reset_ok)
    val sshTrustedHostsResetFailed = stringResource(Strings.git_ssh_trusted_hosts_reset_failed)

    var keys by remember { mutableStateOf<List<GitSshKeyMeta>>(emptyList()) }
    var bindings by remember { mutableStateOf<List<GitSshHostBinding>>(emptyList()) }
    var defaultKeyName by remember { mutableStateOf<String?>(null) }

    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    var generateDialogVisible by remember { mutableStateOf(false) }
    var generateName by remember { mutableStateOf("id_ed25519") }
    var generateComment by remember { mutableStateOf("") }
    var importDialogVisible by remember { mutableStateOf(false) }
    var importName by remember { mutableStateOf("") }
    var importComment by remember { mutableStateOf("") }
    var importPem by remember { mutableStateOf<String?>(null) }

    var keyDetails by remember { mutableStateOf<GitSshKeyMeta?>(null) }
    var keyDetailsPub by remember { mutableStateOf<String?>(null) }

    var selectingDefaultKey by remember { mutableStateOf(false) }
    var trustedHostsResetDialogVisible by remember { mutableStateOf(false) }
    var bindingEditor by remember { mutableStateOf<GitSshHostBinding?>(null) }
    var bindingDialogVisible by remember { mutableStateOf(false) }
    var bindingHost by remember { mutableStateOf("") }
    var bindingKeyName by remember { mutableStateOf("") }
    var bindingPort by remember { mutableStateOf("") }
    var selectingBindingKey by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            isLoading = true
            error = null
            try {
                keys = sshManager.listKeys()
                bindings = sshManager.listHostBindings()
                defaultKeyName = sshManager.getDefaultKeyName()
            } catch (t: Throwable) {
                error = t.message ?: sshErrorLoadFailed
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        refresh()
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val pem = withContext(Dispatchers.IO) { readTextFromUri(context, uri) }
                importPem = pem
                importName = GitSettingsSectionSupport.suggestKeyName(uri.lastPathSegment)
                importComment = ""
                importDialogVisible = true
            }.onFailure { t ->
                Toast.makeText(context, t.message ?: sshErrorImportFailed, Toast.LENGTH_LONG).show()
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
    SettingsCategoryTitle(stringResource(Strings.settings_cat_git_ssh))

    SettingsCard {
        SettingsClickableItem(
            title = stringResource(Strings.git_ssh_generate_key_title),
            subtitle = stringResource(Strings.git_ssh_generate_key_desc),
            value = null,
            onClick = {
                generateName = defaultKeyName ?: "id_ed25519"
                generateComment = ""
                generateDialogVisible = true
            },
            showDivider = true
        )

        SettingsClickableItem(
            title = stringResource(Strings.git_ssh_import_key_title),
            subtitle = stringResource(Strings.git_ssh_import_key_desc),
            value = null,
            onClick = {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                importLauncher.launch(intent)
            },
            showDivider = true
        )

        SettingsClickableItem(
            title = stringResource(Strings.git_ssh_default_key_title),
            subtitle = stringResource(Strings.git_ssh_default_key_desc),
            value = defaultKeyName ?: stringResource(Strings.git_ssh_default_key_none),
            onClick = { selectingDefaultKey = true },
            showDivider = true
        )

        SettingsClickableItem(
            title = stringResource(Strings.git_ssh_trusted_hosts_reset_title),
            subtitle = stringResource(Strings.git_ssh_trusted_hosts_reset_desc),
            value = null,
            onClick = { trustedHostsResetDialogVisible = true },
            showDivider = false,
        )
    }

    if (error != null) {
        Spacer(modifier = Modifier.height(8.dp))
        SettingsCard {
            SettingsClickableItem(
                title = stringResource(Strings.git_ssh_error_title),
                subtitle = error,
                value = null,
                onClick = { refresh() },
                showDivider = false
            )
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
    SettingsCategoryTitle(stringResource(Strings.git_ssh_keys_title))

    SettingsCard {
        if (keys.isEmpty()) {
            SettingsClickableItem(
                title = stringResource(Strings.git_ssh_keys_empty_title),
                subtitle = stringResource(Strings.git_ssh_keys_empty_desc),
                value = null,
                onClick = { generateDialogVisible = true },
                showDivider = false
            )
        } else {
            keys.forEachIndexed { index, key ->
                SettingsClickableItem(
                    title = key.name,
                    subtitle = key.comment ?: stringResource(Strings.git_ssh_key_item_subtitle, key.type),
                    value = if (defaultKeyName == key.name) stringResource(Strings.git_ssh_default_badge) else null,
                    onClick = {
                        keyDetails = key
                        keyDetailsPub = null
                        scope.launch { keyDetailsPub = sshManager.readPublicKey(key.name) }
                    },
                    showDivider = index != keys.lastIndex
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
    SettingsCategoryTitle(stringResource(Strings.git_ssh_bindings_title))

    SettingsCard {
        SettingsClickableItem(
            title = stringResource(Strings.git_ssh_binding_add_title),
            subtitle = stringResource(Strings.git_ssh_binding_add_desc),
            value = null,
            onClick = {
                val editorState = GitSettingsSectionSupport.createAddBindingEditorState(
                    defaultKeyName = defaultKeyName,
                    keys = keys,
                )
                bindingEditor = null
                bindingHost = editorState.host
                bindingPort = editorState.port
                bindingKeyName = editorState.keyName
                selectingBindingKey = false
                bindingDialogVisible = true
            },
            showDivider = bindings.isNotEmpty()
        )

        bindings.forEachIndexed { index, binding ->
            SettingsClickableItem(
                title = binding.host,
                subtitle = stringResource(Strings.git_ssh_binding_item_subtitle, binding.keyName),
                value = binding.port?.toString(),
                onClick = {
                    val editorState = GitSettingsSectionSupport.createEditBindingEditorState(binding)
                    bindingEditor = binding
                    bindingHost = editorState.host
                    bindingPort = editorState.port
                    bindingKeyName = editorState.keyName
                    selectingBindingKey = false
                    bindingDialogVisible = true
                },
                showDivider = index != bindings.lastIndex
            )
        }
    }

    // ==================== 对话框 ====================

    if (selectingDefaultKey) {
        val options = GitSettingsSectionSupport.buildDefaultKeyOptions(
            keys = keys,
            noneLabel = stringResource(Strings.git_ssh_default_key_none),
        )
        TinaSingleChoiceDialog(
            title = stringResource(Strings.git_ssh_default_key_pick_title),
            options = options,
            selectedValue = defaultKeyName ?: "__none__",
            onSelected = { selected ->
                val newValue = GitSettingsSectionSupport.resolveSelectedDefaultKey(selected)
                scope.launch {
                    sshManager.setDefaultKeyName(newValue)
                    selectingDefaultKey = false
                    refresh()
                }
            },
            onDismiss = { selectingDefaultKey = false }
        )
    }

    if (trustedHostsResetDialogVisible) {
        TinaAlertDialog(
            onDismissRequest = { trustedHostsResetDialogVisible = false },
            title = { TinaDialogTitleText(stringResource(Strings.git_ssh_trusted_hosts_reset_dialog_title)) },
            text = {
                TinaDialogMessageCard(
                    message = stringResource(Strings.git_ssh_trusted_hosts_reset_dialog_message),
                )
            },
            confirmButton = {
                TinaDangerButton(
                    text = stringResource(Strings.git_ssh_trusted_hosts_reset_confirm),
                    onClick = {
                        scope.launch {
                            runCatching { sshManager.clearTrustedHosts() }
                                .onSuccess {
                                    trustedHostsResetDialogVisible = false
                                    Toast.makeText(context, sshTrustedHostsResetOk, Toast.LENGTH_SHORT).show()
                                }
                                .onFailure {
                                    Toast.makeText(context, sshTrustedHostsResetFailed, Toast.LENGTH_LONG).show()
                                }
                        }
                    },
                )
            },
            dismissButton = {
                TinaTextButton(
                    text = stringResource(Strings.btn_cancel),
                    onClick = { trustedHostsResetDialogVisible = false },
                )
            },
        )
    }

    if (generateDialogVisible) {
        TinaAlertDialog(
            onDismissRequest = { generateDialogVisible = false },
            title = { TinaDialogTitleText(stringResource(Strings.git_ssh_generate_dialog_title)) },
            text = {
                TinaDialogContentColumn {
                    TinaTextField(
                        value = generateName,
                        onValueChange = { generateName = it },
                        label = stringResource(Strings.git_ssh_key_name_label),
                        placeholder = "id_ed25519"
                    )
                    TinaTextField(
                        value = generateComment,
                        onValueChange = { generateComment = it },
                        label = stringResource(Strings.git_ssh_key_comment_label),
                        placeholder = stringResource(Strings.git_ssh_key_comment_placeholder)
                    )
                }
            },
            confirmButton = {
                TinaPrimaryButton(
                    text = stringResource(Strings.btn_ok),
                    onClick = {
                        val name = generateName.trim()
                        if (!GitSshManager.isValidKeyName(name)) {
                            Toast.makeText(context, sshKeyNameInvalid, Toast.LENGTH_LONG).show()
                            return@TinaPrimaryButton
                        }
                        scope.launch {
                            sshManager.generateEd25519Key(name, generateComment.trim().ifBlank { null })
                                .onSuccess {
                                    Toast.makeText(
                                        context,
                                        String.format(Locale.getDefault(), sshGenerateOkTemplate, it.name),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    generateDialogVisible = false
                                    refresh()
                                }
                                .onFailure { t ->
                                    Toast.makeText(context, t.message ?: sshGenerateFailed, Toast.LENGTH_LONG).show()
                                }
                        }
                    }
                )
            },
            dismissButton = {
                TinaTextButton(text = stringResource(Strings.btn_cancel), onClick = { generateDialogVisible = false })
            }
        )
    }

    if (importDialogVisible) {
        TinaAlertDialog(
            onDismissRequest = { importDialogVisible = false },
            title = { TinaDialogTitleText(stringResource(Strings.git_ssh_import_dialog_title)) },
            text = {
                TinaDialogContentColumn {
                    TinaTextField(
                        value = importName,
                        onValueChange = { importName = it },
                        label = stringResource(Strings.git_ssh_key_name_label),
                        placeholder = "id_ed25519"
                    )
                    TinaTextField(
                        value = importComment,
                        onValueChange = { importComment = it },
                        label = stringResource(Strings.git_ssh_key_comment_label),
                        placeholder = stringResource(Strings.git_ssh_key_comment_placeholder)
                    )
                }
            },
            confirmButton = {
                TinaPrimaryButton(
                    text = stringResource(Strings.btn_ok),
                    onClick = {
                        val pem = importPem
                        if (pem.isNullOrBlank()) {
                            Toast.makeText(context, sshErrorImportFailed, Toast.LENGTH_SHORT).show()
                            return@TinaPrimaryButton
                        }
                        val name = importName.trim()
                        if (!GitSshManager.isValidKeyName(name)) {
                            Toast.makeText(context, sshKeyNameInvalid, Toast.LENGTH_LONG).show()
                            return@TinaPrimaryButton
                        }
                        scope.launch {
                            sshManager.importPrivateKey(name, pem, importComment)
                                .onSuccess {
                                    Toast.makeText(
                                        context,
                                        String.format(Locale.getDefault(), sshImportOkTemplate, it.name),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    importDialogVisible = false
                                    importPem = null
                                    refresh()
                                }
                                .onFailure { t ->
                                    Toast.makeText(context, t.message ?: sshErrorImportFailed, Toast.LENGTH_LONG).show()
                                }
                        }
                    }
                )
            },
            dismissButton = {
                TinaTextButton(
                    text = stringResource(Strings.btn_cancel),
                    onClick = {
                        importDialogVisible = false
                        importPem = null
                    }
                )
            }
        )
    }

    if (keyDetails != null) {
        val key = keyDetails!!
        TinaAlertDialog(
            onDismissRequest = { keyDetails = null },
            title = { TinaDialogTitleText(key.name) },
            text = {
                val pub = keyDetailsPub
                val subtitle = pub ?: stringResource(Strings.git_ssh_pubkey_loading)
                TinaDialogContentColumn {
                    TinaDialogMessageCard(message = subtitle)
                }
            },
            confirmButton = {
                TinaPrimaryButton(
                    text = stringResource(Strings.git_ssh_btn_copy_pubkey),
                    onClick = {
                        val pub = keyDetailsPub
                        if (pub.isNullOrBlank()) {
                            Toast.makeText(context, sshPubkeyMissing, Toast.LENGTH_SHORT).show()
                            return@TinaPrimaryButton
                        }
                        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("ssh-public-key", pub))
                        Toast.makeText(context, sshPubkeyCopied, Toast.LENGTH_SHORT).show()
                    }
                )
            },
            dismissButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TinaDangerButton(
                        text = stringResource(Strings.btn_delete),
                        onClick = {
                            scope.launch {
                                sshManager.deleteKey(key.name)
                                    .onSuccess {
                                        Toast.makeText(
                                            context,
                                            String.format(Locale.getDefault(), sshDeleteOkTemplate, key.name),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        keyDetails = null
                                        refresh()
                                    }
                                    .onFailure { t ->
                                        Toast.makeText(context, t.message ?: sshDeleteFailed, Toast.LENGTH_LONG).show()
                                    }
                            }
                        }
                    )
                    TinaTextButton(
                        text = stringResource(Strings.btn_cancel),
                        onClick = { keyDetails = null }
                    )
                }
            }
        )
    }

    if (bindingDialogVisible) {
        val editing = bindingEditor
        TinaAlertDialog(
            onDismissRequest = {
                val clearedState = GitSettingsSectionSupport.clearBindingEditorState()
                bindingDialogVisible = false
                bindingEditor = null
                bindingHost = clearedState.host
                bindingPort = clearedState.port
                bindingKeyName = clearedState.keyName
            },
            title = {
                TinaDialogTitleText(
                    stringResource(
                        GitSettingsSectionSupport.resolveBindingDialogTitleRes(editing != null)
                    )
                )
            },
            text = {
                TinaDialogContentColumn {
                    TinaTextField(
                        value = bindingHost,
                        onValueChange = { bindingHost = it },
                        label = stringResource(Strings.git_ssh_binding_host_label),
                        placeholder = "github.com"
                    )
                    TinaTextField(
                        value = bindingPort,
                        onValueChange = { bindingPort = it },
                        label = stringResource(Strings.git_ssh_binding_port_label),
                        placeholder = "22"
                    )
                    SettingsClickableItem(
                        title = stringResource(Strings.git_ssh_binding_key_label),
                        subtitle = stringResource(Strings.git_ssh_binding_key_desc),
                        value = GitSettingsSectionSupport.resolveBindingKeyDisplayValue(
                            keyName = bindingKeyName,
                            notSelectedLabel = stringResource(Strings.git_ssh_binding_key_not_selected),
                        ),
                        onClick = { selectingBindingKey = true },
                        showDivider = false
                    )
                }
            },
            confirmButton = {
                TinaPrimaryButton(
                    text = stringResource(Strings.btn_ok),
                    onClick = {
                        if (GitSettingsSectionSupport.validateSshBindingHost(bindingHost) != null) {
                            Toast.makeText(context, sshBindingHostInvalid, Toast.LENGTH_LONG).show()
                            return@TinaPrimaryButton
                        }
                        if (GitSettingsSectionSupport.validateSshBindingPort(bindingPort) != null) {
                            Toast.makeText(context, sshBindingPortInvalid, Toast.LENGTH_LONG).show()
                            return@TinaPrimaryButton
                        }
                        val draft = GitSettingsSectionSupport.resolveBindingDraft(
                            host = bindingHost,
                            keyName = bindingKeyName,
                            port = bindingPort,
                        )
                        scope.launch {
                            runCatching {
                                sshManager.upsertHostBinding(draft)
                            }.onSuccess {
                                val clearedState = GitSettingsSectionSupport.clearBindingEditorState()
                                Toast.makeText(context, sshBindingSaveOk, Toast.LENGTH_SHORT).show()
                                bindingDialogVisible = false
                                bindingEditor = null
                                bindingHost = clearedState.host
                                bindingPort = clearedState.port
                                bindingKeyName = clearedState.keyName
                                refresh()
                            }.onFailure { t ->
                                Toast.makeText(context, t.message ?: sshBindingSaveFailed, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                )
            },
            dismissButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (editing != null) {
                        TinaDangerButton(
                            text = stringResource(Strings.btn_delete),
                            onClick = {
                                scope.launch {
                                    val clearedState = GitSettingsSectionSupport.clearBindingEditorState()
                                    sshManager.deleteHostBinding(editing.host, editing.port)
                                    Toast.makeText(context, sshBindingDeleteOk, Toast.LENGTH_SHORT).show()
                                    bindingDialogVisible = false
                                    bindingEditor = null
                                    bindingHost = clearedState.host
                                    bindingPort = clearedState.port
                                    bindingKeyName = clearedState.keyName
                                    refresh()
                                }
                            }
                        )
                    }
                    TinaTextButton(
                        text = stringResource(Strings.btn_cancel),
                        onClick = {
                            val clearedState = GitSettingsSectionSupport.clearBindingEditorState()
                            bindingDialogVisible = false
                            bindingEditor = null
                            bindingHost = clearedState.host
                            bindingPort = clearedState.port
                            bindingKeyName = clearedState.keyName
                        }
                    )
                }
            }
        )
    }

    if (selectingBindingKey) {
        val options = keys.map { it.name to it.name }
        TinaSingleChoiceDialog(
            title = stringResource(Strings.git_ssh_binding_key_pick_title),
            options = options,
            selectedValue = bindingKeyName,
            onSelected = { selected ->
                bindingKeyName = selected
                selectingBindingKey = false
            },
            onDismiss = { selectingBindingKey = false }
        )
    }
}

@Composable
private fun GitHubRegistryContent() {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val saveOk = stringResource(Strings.github_registry_proxy_save_ok)

    var enabled by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var customMirrorUrl by remember { mutableStateOf("") }
    var errorRes by remember { mutableStateOf<Int?>(null) }

    fun loadSettings() {
        val editorState = GitSettingsSectionSupport.createGitHubRegistryProxyEditorState(
            GitHubRegistryProxyConfig.load(appContext)
        )
        enabled = editorState.enabled
        host = editorState.host
        port = editorState.port
        customMirrorUrl = editorState.customMirrorUrl
        errorRes = null
    }

    LaunchedEffect(Unit) {
        loadSettings()
    }

    Spacer(modifier = Modifier.height(8.dp))
    SettingsCategoryTitle(stringResource(Strings.settings_cat_github_registry))

    SettingsCard {
        SettingsSwitchItem(
            title = stringResource(Strings.github_registry_proxy_enabled_title),
            subtitle = stringResource(Strings.github_registry_proxy_enabled_desc),
            checked = enabled,
            onCheckedChange = {
                enabled = it
                errorRes = null
            },
            showDivider = false,
        )
    }

    SettingsCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(Strings.github_registry_proxy_scope_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TinaTextField(
                value = customMirrorUrl,
                onValueChange = {
                    customMirrorUrl = it
                    errorRes = null
                },
                label = stringResource(Strings.github_registry_mirror_url_label),
                placeholder = stringResource(Strings.github_registry_mirror_url_placeholder),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            TinaTextField(
                value = host,
                onValueChange = {
                    host = it
                    errorRes = null
                },
                label = stringResource(Strings.github_registry_proxy_host_label),
                placeholder = stringResource(Strings.github_registry_proxy_host_placeholder),
                enabled = enabled,
            )
            TinaTextField(
                value = port,
                onValueChange = {
                    port = it.filter(Char::isDigit)
                    errorRes = null
                },
                label = stringResource(Strings.github_registry_proxy_port_label),
                placeholder = stringResource(Strings.github_registry_proxy_port_placeholder),
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            errorRes?.let { resId ->
                TinaDialogMessageCard(
                    message = stringResource(resId),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f),
                    textColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            TinaPrimaryButton(
                text = stringResource(Strings.btn_save),
                onClick = {
                    val result = GitSettingsSectionSupport.resolveGitHubRegistryProxySettings(
                        enabled = enabled,
                        rawHost = host,
                        rawPort = port,
                        rawCustomMirrorUrl = customMirrorUrl,
                    )
                    val settings = result.settings
                    if (settings == null) {
                        errorRes = result.errorRes
                        return@TinaPrimaryButton
                    }

                    GitHubRegistryProxyConfig.save(appContext, settings)
                    val editorState = GitSettingsSectionSupport.createGitHubRegistryProxyEditorState(settings)
                    enabled = editorState.enabled
                    host = editorState.host
                    port = editorState.port
                    customMirrorUrl = editorState.customMirrorUrl
                    errorRes = null
                    Toast.makeText(context, saveOk, Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun readTextFromUri(context: android.content.Context, uri: Uri): String {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var totalBytes = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            totalBytes += read
            if (totalBytes > GitSshManager.MAX_PRIVATE_KEY_BYTES) {
                throw IllegalArgumentException(
                    Strings.git_ssh_private_key_too_large.strOr(
                        context,
                        GitSshManager.MAX_PRIVATE_KEY_BYTES / (1024 * 1024),
                    ),
                )
            }
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }
    throw IllegalStateException(Strings.git_error_cannot_read_file.strOr(context))
}
