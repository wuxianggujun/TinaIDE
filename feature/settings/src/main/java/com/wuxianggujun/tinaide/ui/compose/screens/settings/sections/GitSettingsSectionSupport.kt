package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import androidx.annotation.StringRes
import com.wuxianggujun.tinaide.core.git.GitCredential
import com.wuxianggujun.tinaide.core.git.ssh.GitSshHostBinding
import com.wuxianggujun.tinaide.core.git.ssh.GitSshKeyMeta
import com.wuxianggujun.tinaide.core.git.ssh.GitSshManager
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.network.registry.GitHubRegistryConfig
import com.wuxianggujun.tinaide.core.network.registry.GitHubRegistryProxySettings
import java.net.URI

internal data class GitHttpsEditorState(
    val host: String,
    val username: String,
    val token: String,
    val isEdit: Boolean,
)

internal data class GitHttpsCredentialDraft(
    val resolvedHost: String,
    val username: String,
    val token: String,
)

internal data class GitSshBindingEditorState(
    val host: String,
    val keyName: String,
    val port: String,
)

internal data class GitHubRegistryProxyEditorState(
    val enabled: Boolean,
    val host: String,
    val port: String,
    val customMirrorUrl: String,
)

internal data class GitHubRegistryProxyResolveResult(
    val settings: GitHubRegistryProxySettings?,
    @param:StringRes val errorRes: Int?,
)

internal object GitSettingsSectionSupport {
    fun createAddHttpsEditorState(): GitHttpsEditorState = GitHttpsEditorState(
        host = "",
        username = "",
        token = "",
        isEdit = false,
    )

    fun createEditHttpsEditorState(credential: GitCredential): GitHttpsEditorState = GitHttpsEditorState(
        host = credential.host,
        username = credential.username,
        token = "",
        isEdit = true,
    )

    fun resolveHttpsCredentialDraft(
        rawHost: String,
        rawUsername: String,
        rawToken: String,
    ): GitHttpsCredentialDraft = GitHttpsCredentialDraft(
        resolvedHost = extractHost(rawHost.trim()),
        username = rawUsername.trim().ifBlank { "oauth2" },
        token = rawToken.trim(),
    )

    fun isHttpsHostInvalid(resolvedHost: String): Boolean = resolvedHost.isBlank()

    fun isNewHttpsTokenMissing(isEdit: Boolean, token: String): Boolean = !isEdit && token.isBlank()

    fun resolveHttpsCredentialToken(
        inputToken: String,
        existingToken: String?,
    ): String? = inputToken.ifBlank { existingToken.orEmpty() }.ifBlank { null }

    fun sortHttpsCredentials(credentials: List<GitCredential>): List<GitCredential> = credentials.sortedBy { it.host.lowercase() }

    fun buildDefaultKeyOptions(
        keys: List<GitSshKeyMeta>,
        noneLabel: String,
    ): List<Pair<String, String>> = (keys.map { it.name to it.name } + ("__none__" to noneLabel)).distinct()

    fun resolveSelectedDefaultKey(selected: String): String? = selected.takeIf { it != "__none__" }

    fun createAddBindingEditorState(
        defaultKeyName: String?,
        keys: List<GitSshKeyMeta>,
    ): GitSshBindingEditorState = GitSshBindingEditorState(
        host = "",
        keyName = defaultKeyName ?: keys.firstOrNull()?.name.orEmpty(),
        port = "",
    )

    fun createEditBindingEditorState(binding: GitSshHostBinding): GitSshBindingEditorState = GitSshBindingEditorState(
        host = binding.host,
        keyName = binding.keyName,
        port = binding.port?.toString().orEmpty(),
    )

    fun clearBindingEditorState(): GitSshBindingEditorState = GitSshBindingEditorState(
        host = "",
        keyName = "",
        port = "",
    )

    @StringRes
    fun resolveBindingDialogTitleRes(isEditing: Boolean): Int = if (isEditing) {
        Strings.git_ssh_binding_edit_title
    } else {
        Strings.git_ssh_binding_add_title
    }

    fun resolveBindingDraft(
        host: String,
        keyName: String,
        port: String,
    ): GitSshHostBinding {
        val normalizedPort = port.trim()
        require(validateSshBindingHost(host) == null) { "SSH host is invalid" }
        require(validateSshBindingPort(normalizedPort) == null) { "SSH port is invalid" }
        return GitSshHostBinding(
            host = host.trim(),
            keyName = keyName.trim(),
            port = normalizedPort.takeIf(String::isNotEmpty)?.toInt(),
        )
    }

    @StringRes
    fun validateSshBindingHost(input: String): Int? =
        if (GitSshManager.isValidHost(input)) null else Strings.git_ssh_binding_host_invalid

    @StringRes
    fun validateSshBindingPort(input: String): Int? {
        val normalized = input.trim()
        if (normalized.isEmpty()) return null
        val port = normalized.toIntOrNull()
        return if (port != null && port in 1..65535) {
            null
        } else {
            Strings.git_ssh_binding_port_invalid
        }
    }

    fun resolveBindingKeyDisplayValue(
        keyName: String,
        notSelectedLabel: String,
    ): String = keyName.ifBlank { notSelectedLabel }

    fun createGitHubRegistryProxyEditorState(
        settings: GitHubRegistryProxySettings,
    ): GitHubRegistryProxyEditorState = GitHubRegistryProxyEditorState(
        enabled = settings.enabled,
        host = settings.host,
        port = settings.port.takeIf { it > 0 }?.toString().orEmpty(),
        customMirrorUrl = settings.customMirrorUrl,
    )

    fun resolveGitHubRegistryProxySettings(
        enabled: Boolean,
        rawHost: String,
        rawPort: String,
        rawCustomMirrorUrl: String = "",
    ): GitHubRegistryProxyResolveResult {
        val hostWithOptionalPort = normalizeGitHubRegistryProxyHost(rawHost)
        val hasSinglePortDelimiter = hostWithOptionalPort.count { it == ':' } == 1
        val host = if (hasSinglePortDelimiter) {
            hostWithOptionalPort.substringBeforeLast(":")
        } else {
            hostWithOptionalPort
        }
        val hostPort = if (hasSinglePortDelimiter) {
            hostWithOptionalPort.substringAfterLast(":").takeIf { it.all(Char::isDigit) }
        } else {
            null
        }
        val portText = rawPort.trim().ifBlank { hostPort.orEmpty() }
        val port = portText.toIntOrNull() ?: 0
        val customMirrorUrl = GitHubRegistryConfig.normalizeGitHubProxyPrefix(rawCustomMirrorUrl)

        if (enabled && host.isBlank()) {
            return GitHubRegistryProxyResolveResult(
                settings = null,
                errorRes = Strings.github_registry_proxy_error_host_required,
            )
        }
        if (enabled && port !in 1..65535) {
            return GitHubRegistryProxyResolveResult(
                settings = null,
                errorRes = Strings.github_registry_proxy_error_port_invalid,
            )
        }
        if (rawCustomMirrorUrl.isNotBlank() && customMirrorUrl == null) {
            return GitHubRegistryProxyResolveResult(
                settings = null,
                errorRes = Strings.github_registry_mirror_url_error_invalid,
            )
        }

        return GitHubRegistryProxyResolveResult(
            settings = GitHubRegistryProxySettings(
                enabled = enabled,
                host = host,
                port = port,
                customMirrorUrl = customMirrorUrl.orEmpty(),
            ),
            errorRes = null,
        )
    }

    fun extractHost(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ""

        if (trimmed.contains("://")) {
            val uri = runCatching { URI(trimmed) }.getOrNull()
            return uri?.host?.trim().orEmpty()
        }

        if (trimmed.contains("/") && !trimmed.contains(" ")) {
            return trimmed.substringBefore("/").trim()
        }

        return trimmed
    }

    private fun normalizeGitHubRegistryProxyHost(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ""

        if (trimmed.contains("://")) {
            val uri = runCatching { URI(trimmed) }.getOrNull() ?: return ""
            return buildString {
                append(uri.host?.trim().orEmpty())
                if (uri.port > 0) append(":").append(uri.port)
            }
        }

        return trimmed
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore("/")
            .trim()
    }

    fun suggestKeyName(lastPathSegment: String?): String {
        val base = lastPathSegment
            ?.substringAfterLast('/')
            ?.trim()
            .orEmpty()
            .removeSuffix(".pub")
            .removeSuffix(".key")
            .removeSuffix(".pem")
            .removeSuffix(".txt")
            .ifBlank { "id_ed25519" }
        return base.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}
