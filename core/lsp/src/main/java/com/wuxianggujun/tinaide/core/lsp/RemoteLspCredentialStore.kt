package com.wuxianggujun.tinaide.core.lsp

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

internal class RemoteLspCredentialStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            PREFERENCES_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun readToken(): String? = preferences.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }

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
        val editor = preferences.edit()
        if (normalizedToken == null) editor.remove(KEY_TOKEN) else editor.putString(KEY_TOKEN, normalizedToken)
        check(editor.commit()) { "Failed to persist remote LSP authentication token" }
    }

    private companion object {
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
