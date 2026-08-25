package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.git.GitCredential
import com.wuxianggujun.tinaide.core.git.ssh.GitSshHostBinding
import com.wuxianggujun.tinaide.core.git.ssh.GitSshKeyMeta
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.network.registry.GitHubRegistryProxySettings
import org.junit.Test

class GitSettingsSectionSupportTest {

    @Test
    fun httpsEditorHelpers_shouldCreateStableAddEditStatesAndDrafts() {
        assertThat(
            GitSettingsSectionSupport.createAddHttpsEditorState()
        ).isEqualTo(
            GitHttpsEditorState(
                host = "",
                username = "",
                token = "",
                isEdit = false,
            )
        )
        assertThat(
            GitSettingsSectionSupport.createEditHttpsEditorState(
                GitCredential(
                    protocol = "https",
                    host = "github.com",
                    username = "alice",
                    password = "secret",
                )
            )
        ).isEqualTo(
            GitHttpsEditorState(
                host = "github.com",
                username = "alice",
                token = "",
                isEdit = true,
            )
        )

        val draft = GitSettingsSectionSupport.resolveHttpsCredentialDraft(
            rawHost = " https://github.com/user/repo.git ",
            rawUsername = " ",
            rawToken = "  token  ",
        )
        assertThat(draft).isEqualTo(
            GitHttpsCredentialDraft(
                resolvedHost = "github.com",
                username = "oauth2",
                token = "token",
            )
        )
        assertThat(GitSettingsSectionSupport.isHttpsHostInvalid("")).isTrue()
        assertThat(GitSettingsSectionSupport.isHttpsHostInvalid("github.com")).isFalse()
        assertThat(
            GitSettingsSectionSupport.isNewHttpsTokenMissing(
                isEdit = false,
                token = "",
            )
        ).isTrue()
        assertThat(
            GitSettingsSectionSupport.isNewHttpsTokenMissing(
                isEdit = true,
                token = "",
            )
        ).isFalse()
        assertThat(
            GitSettingsSectionSupport.resolveHttpsCredentialToken(
                inputToken = "",
                existingToken = "persisted",
            )
        ).isEqualTo("persisted")
        assertThat(
            GitSettingsSectionSupport.resolveHttpsCredentialToken(
                inputToken = "new",
                existingToken = "persisted",
            )
        ).isEqualTo("new")
        assertThat(
            GitSettingsSectionSupport.resolveHttpsCredentialToken(
                inputToken = "",
                existingToken = "",
            )
        ).isNull()
    }

    @Test
    fun extractHostAndSortHttpsCredentials_shouldNormalizeCommonInputs() {
        assertThat(
            GitSettingsSectionSupport.extractHost("https://github.com/user/repo.git")
        ).isEqualTo("github.com")
        assertThat(
            GitSettingsSectionSupport.extractHost("gitlab.com/group/project")
        ).isEqualTo("gitlab.com")
        assertThat(
            GitSettingsSectionSupport.extractHost("  github.com  ")
        ).isEqualTo("github.com")
        assertThat(
            GitSettingsSectionSupport.extractHost("not a uri://")
        ).isEmpty()

        assertThat(
            GitSettingsSectionSupport.sortHttpsCredentials(
                listOf(
                    GitCredential(
                        protocol = "https",
                        host = "GitHub.com",
                        username = "b",
                        password = "2",
                    ),
                    GitCredential(
                        protocol = "https",
                        host = "android.googlesource.com",
                        username = "a",
                        password = "1",
                    ),
                )
            )
        ).containsExactly(
            GitCredential(
                protocol = "https",
                host = "android.googlesource.com",
                username = "a",
                password = "1",
            ),
            GitCredential(
                protocol = "https",
                host = "GitHub.com",
                username = "b",
                password = "2",
            ),
        ).inOrder()
    }

    @Test
    fun githubRegistryProxySettings_shouldResolveCommonInputs() {
        assertThat(
            GitSettingsSectionSupport.resolveGitHubRegistryProxySettings(
                enabled = false,
                rawHost = " ",
                rawPort = " ",
            )
        ).isEqualTo(
            GitHubRegistryProxyResolveResult(
                settings = GitHubRegistryProxySettings(
                    enabled = false,
                    host = "",
                    port = 0,
                ),
                errorRes = null,
            )
        )

        assertThat(
            GitSettingsSectionSupport.resolveGitHubRegistryProxySettings(
                enabled = true,
                rawHost = " 127.0.0.1 ",
                rawPort = " 7890 ",
            )
        ).isEqualTo(
            GitHubRegistryProxyResolveResult(
                settings = GitHubRegistryProxySettings(
                    enabled = true,
                    host = "127.0.0.1",
                    port = 7890,
                ),
                errorRes = null,
            )
        )

        assertThat(
            GitSettingsSectionSupport.resolveGitHubRegistryProxySettings(
                enabled = true,
                rawHost = " http://127.0.0.1:7890/proxy ",
                rawPort = "",
                rawCustomMirrorUrl = " ghproxy.example.com ",
            )
        ).isEqualTo(
            GitHubRegistryProxyResolveResult(
                settings = GitHubRegistryProxySettings(
                    enabled = true,
                    host = "127.0.0.1",
                    port = 7890,
                    customMirrorUrl = "https://ghproxy.example.com/",
                ),
                errorRes = null,
            )
        )
    }

    @Test
    fun githubRegistryProxySettings_shouldValidateEnabledInputs() {
        assertThat(
            GitSettingsSectionSupport.resolveGitHubRegistryProxySettings(
                enabled = true,
                rawHost = "",
                rawPort = "7890",
            )
        ).isEqualTo(
            GitHubRegistryProxyResolveResult(
                settings = null,
                errorRes = Strings.github_registry_proxy_error_host_required,
            )
        )

        assertThat(
            GitSettingsSectionSupport.resolveGitHubRegistryProxySettings(
                enabled = false,
                rawHost = "",
                rawPort = "",
                rawCustomMirrorUrl = "ftp://proxy.example.com",
            )
        ).isEqualTo(
            GitHubRegistryProxyResolveResult(
                settings = null,
                errorRes = Strings.github_registry_mirror_url_error_invalid,
            )
        )

        assertThat(
            GitSettingsSectionSupport.resolveGitHubRegistryProxySettings(
                enabled = true,
                rawHost = "127.0.0.1",
                rawPort = "bad",
            )
        ).isEqualTo(
            GitHubRegistryProxyResolveResult(
                settings = null,
                errorRes = Strings.github_registry_proxy_error_port_invalid,
            )
        )
    }

    @Test
    fun sshBindingHelpers_shouldCreateEditStateAndResolveDrafts() {
        val keys = listOf(
            GitSshKeyMeta(name = "id_ed25519", type = "ssh-ed25519", comment = null),
            GitSshKeyMeta(name = "work_key", type = "ssh-rsa", comment = "work"),
        )

        assertThat(
            GitSettingsSectionSupport.createAddBindingEditorState(
                defaultKeyName = "work_key",
                keys = keys,
            )
        ).isEqualTo(
            GitSshBindingEditorState(
                host = "",
                keyName = "work_key",
                port = "",
            )
        )
        assertThat(
            GitSettingsSectionSupport.createAddBindingEditorState(
                defaultKeyName = null,
                keys = keys,
            )
        ).isEqualTo(
            GitSshBindingEditorState(
                host = "",
                keyName = "id_ed25519",
                port = "",
            )
        )

        val binding = GitSshHostBinding(
            host = "github.com",
            keyName = "id_ed25519",
            port = 2222,
        )
        assertThat(
            GitSettingsSectionSupport.createEditBindingEditorState(binding)
        ).isEqualTo(
            GitSshBindingEditorState(
                host = "github.com",
                keyName = "id_ed25519",
                port = "2222",
            )
        )
        assertThat(
            GitSettingsSectionSupport.clearBindingEditorState()
        ).isEqualTo(
            GitSshBindingEditorState(
                host = "",
                keyName = "",
                port = "",
            )
        )
        assertThat(
            GitSettingsSectionSupport.resolveBindingDialogTitleRes(false)
        ).isEqualTo(Strings.git_ssh_binding_add_title)
        assertThat(
            GitSettingsSectionSupport.resolveBindingDialogTitleRes(true)
        ).isEqualTo(Strings.git_ssh_binding_edit_title)
        assertThat(
            GitSettingsSectionSupport.resolveBindingDraft(
                host = " github.com ",
                keyName = " id_ed25519 ",
                port = " 22 ",
            )
        ).isEqualTo(
            GitSshHostBinding(
                host = "github.com",
                keyName = "id_ed25519",
                port = 22,
            )
        )
        assertThat(
            GitSettingsSectionSupport.resolveBindingDraft(
                host = "github.com",
                keyName = "id_ed25519",
                port = "",
            )
        ).isEqualTo(
            GitSshHostBinding(
                host = "github.com",
                keyName = "id_ed25519",
                port = null,
            )
        )
        assertThat(
            GitSettingsSectionSupport.resolveBindingKeyDisplayValue(
                keyName = "",
                notSelectedLabel = "未选择",
            )
        ).isEqualTo("未选择")

        assertThat(GitSettingsSectionSupport.validateSshBindingHost("github.com")).isNull()
        assertThat(GitSettingsSectionSupport.validateSshBindingHost("host/path"))
            .isEqualTo(Strings.git_ssh_binding_host_invalid)
        assertThat(GitSettingsSectionSupport.validateSshBindingPort("")).isNull()
        assertThat(GitSettingsSectionSupport.validateSshBindingPort("65535")).isNull()
        assertThat(GitSettingsSectionSupport.validateSshBindingPort("bad"))
            .isEqualTo(Strings.git_ssh_binding_port_invalid)
        assertThat(GitSettingsSectionSupport.validateSshBindingPort("65536"))
            .isEqualTo(Strings.git_ssh_binding_port_invalid)
        assertThat(
            runCatching {
                GitSettingsSectionSupport.resolveBindingDraft("github.com", "id_ed25519", "bad")
            }.isFailure
        ).isTrue()
    }

    @Test
    fun defaultKeyOptionsAndSuggestedKeyName_shouldStayDeterministic() {
        val keys = listOf(
            GitSshKeyMeta(name = "id_ed25519", type = "ssh-ed25519", comment = null),
            GitSshKeyMeta(name = "id_ed25519", type = "ssh-ed25519", comment = "dup"),
        )

        assertThat(
            GitSettingsSectionSupport.buildDefaultKeyOptions(
                keys = keys,
                noneLabel = "无",
            )
        ).containsExactly(
            "id_ed25519" to "id_ed25519",
            "__none__" to "无",
        ).inOrder()
        assertThat(
            GitSettingsSectionSupport.resolveSelectedDefaultKey("__none__")
        ).isNull()
        assertThat(
            GitSettingsSectionSupport.resolveSelectedDefaultKey("id_ed25519")
        ).isEqualTo("id_ed25519")

        assertThat(
            GitSettingsSectionSupport.suggestKeyName("My Key.pem")
        ).isEqualTo("My_Key")
        assertThat(
            GitSettingsSectionSupport.suggestKeyName("foo/bar/work.pub")
        ).isEqualTo("work")
        assertThat(
            GitSettingsSectionSupport.suggestKeyName(null)
        ).isEqualTo("id_ed25519")
    }
}
