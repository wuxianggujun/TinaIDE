package com.wuxianggujun.tinaide.core.git.ssh

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Locale

/** Persists SSH server-key fingerprints using trust on first use (TOFU). */
internal class GitKnownHostsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun accept(host: String, port: Int, serverKey: PublicKey): Boolean {
        return synchronized(STORE_LOCK) {
            val endpoint = endpointKey(host, port)
            val fingerprint = fingerprint(serverKey)
            val trusted = preferences.getString(endpoint, null)
            if (trusted != null) {
                return@synchronized MessageDigest.isEqual(
                    trusted.toByteArray(Charsets.US_ASCII),
                    fingerprint.toByteArray(Charsets.US_ASCII),
                )
            }
            preferences.edit().putString(endpoint, fingerprint).commit()
        }
    }

    fun remove(host: String, port: Int): Boolean = synchronized(STORE_LOCK) {
        preferences.edit().remove(endpointKey(host, port)).commit()
    }

    fun clear(): Boolean = synchronized(STORE_LOCK) {
        preferences.edit().clear().commit()
    }

    internal fun fingerprint(serverKey: PublicKey): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(serverKey.encoded)
        return "SHA256:${Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING)}"
    }

    private fun endpointKey(host: String, port: Int): String =
        "${host.trim().lowercase(Locale.ROOT)}:${port.takeIf { it > 0 } ?: DEFAULT_SSH_PORT}"

    private companion object {
        private const val PREFERENCES_NAME = "git_ssh_known_hosts"
        private const val DEFAULT_SSH_PORT = 22
        private val STORE_LOCK = Any()
    }
}
