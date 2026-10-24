package com.wuxianggujun.tinaide.core.lsp

import android.content.Context
import android.content.SharedPreferences
import com.wuxianggujun.tinaide.core.security.EncryptedPreferencesFactory
import timber.log.Timber

internal class RemoteLspCredentialStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences: SharedPreferences? by lazy {
        EncryptedPreferencesFactory.open(appContext, PREFERENCES_NAME)
    }

    fun readToken(): String? = try {
        preferences?.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        // 损坏的历史密文不应阻断启动或 LSP 连接，按“未配置令牌”处理。
        Timber.tag(TAG).w(e, "Failed to read remote LSP authentication token")
        null
    }

    fun writeToken(token: String?) {
        val normalizedToken = RemoteLspAuthenticationTokenPolicy.normalize(token)
        when (RemoteLspAuthenticationTokenPolicy.validate(normalizedToken)) {
            RemoteLspAuthenticationTokenValidation.VALID -> Unit
            RemoteLspAuthenticationTokenValidation.TOO_LONG -> {
                throw IllegalArgumentException("Remote LSP authentication token is too long")
            }
            RemoteLspAuthenticationTokenValidation.CONTROL_CHARACTER -> {
                throw IllegalArgumentException("Remote LSP authentication token contains control characters")
            }
        }
        val store = checkNotNull(preferences) {
            "Encrypted remote LSP credential store is unavailable"
        }
        val editor = store.edit()
        if (normalizedToken == null) editor.remove(KEY_TOKEN) else editor.putString(KEY_TOKEN, normalizedToken)
        check(editor.commit()) { "Failed to persist remote LSP authentication token" }
    }

    private companion object {
        private const val TAG = "RemoteLspCredentialStore"
        private const val PREFERENCES_NAME = "remote_lsp_credentials"
        private const val KEY_TOKEN = "authentication_token"
    }
}

enum class RemoteLspAuthenticationTokenValidation {
    VALID,
    TOO_LONG,
    CONTROL_CHARACTER,
}

object RemoteLspAuthenticationTokenPolicy {
    const val MAX_BYTES = 8 * 1024

    fun validate(token: String?): RemoteLspAuthenticationTokenValidation {
        val normalizedToken = normalize(token) ?: return RemoteLspAuthenticationTokenValidation.VALID
        return when {
            normalizedToken.toByteArray(Charsets.UTF_8).size > MAX_BYTES ->
                RemoteLspAuthenticationTokenValidation.TOO_LONG
            normalizedToken.any(Char::isISOControl) -> RemoteLspAuthenticationTokenValidation.CONTROL_CHARACTER
            else -> RemoteLspAuthenticationTokenValidation.VALID
        }
    }

    internal fun normalize(token: String?): String? = token?.trim()?.takeIf(String::isNotEmpty)
}
