package com.wuxianggujun.tinaide.core.git

import android.content.Context
import android.content.SharedPreferences
import com.wuxianggujun.tinaide.core.security.EncryptedPreferencesFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

class AndroidGitCredentialManager(
    private val context: Context
) : GitCredentialManager {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences? by lazy {
        EncryptedPreferencesFactory.open(appContext, PREFS_NAME)
    }

    override suspend fun saveHttpsCredential(host: String, username: String, token: String) {
        val safeHost = host.trim()
        if (safeHost.isEmpty()) return

        withContext(Dispatchers.IO) {
            val store = prefs
            if (store == null) {
                Timber.tag(TAG).e("Encrypted Git credential store is unavailable")
                throw IllegalStateException()
            }
            val hosts = readHosts(store).toMutableSet()
            hosts.add(safeHost)
            store.edit()
                .putString(keyUsername(safeHost), username)
                .putString(keyToken(safeHost), token)
                .putStringSet(KEY_HOSTS, hosts)
                .apply()
        }
    }

    override suspend fun getHttpsCredential(host: String): GitCredential? {
        val safeHost = host.trim()
        if (safeHost.isEmpty()) return null

        return withContext(Dispatchers.IO) {
            val store = prefs ?: return@withContext null
            val username = readString(store, keyUsername(safeHost))?.trim().orEmpty()
            val token = readString(store, keyToken(safeHost))?.trim().orEmpty()
            if (token.isBlank()) return@withContext null

            GitCredential(
                protocol = "https",
                host = safeHost,
                username = username.ifBlank { DEFAULT_USERNAME },
                password = token
            )
        }
    }

    override suspend fun deleteHttpsCredential(host: String) {
        val safeHost = host.trim()
        if (safeHost.isEmpty()) return

        withContext(Dispatchers.IO) {
            val store = prefs ?: return@withContext
            val hosts = readHosts(store).toMutableSet()
            hosts.remove(safeHost)
            store.edit()
                .remove(keyUsername(safeHost))
                .remove(keyToken(safeHost))
                .putStringSet(KEY_HOSTS, hosts)
                .apply()
        }
    }

    override suspend fun listHttpsHosts(): List<String> = withContext(Dispatchers.IO) {
        val store = prefs ?: return@withContext emptyList()
        readHosts(store)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    private fun readHosts(store: SharedPreferences): Set<String> = try {
        store.getStringSet(KEY_HOSTS, emptySet()).orEmpty()
    } catch (e: Exception) {
        // 损坏的密文只影响对应凭据，不阻断 Git 设置页或仓库操作。
        Timber.tag(TAG).w(e, "Failed to read encrypted Git credential hosts")
        emptySet()
    }

    private fun readString(store: SharedPreferences, key: String): String? = try {
        store.getString(key, null)
    } catch (e: Exception) {
        Timber.tag(TAG).w(e, "Failed to read encrypted Git credential")
        null
    }

    private fun keyUsername(host: String) = "https:$host:username"
    private fun keyToken(host: String) = "https:$host:token"

    private companion object {
        private const val TAG = "AndroidGitCredentialManager"
        private const val PREFS_NAME = "git_credentials"
        private const val DEFAULT_USERNAME = "oauth2"
        private const val KEY_HOSTS = "https_hosts"
    }
}
