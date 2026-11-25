package com.wuxianggujun.tinaide.core.network.aegis

import android.content.Context
import java.util.UUID
import timber.log.Timber

/**
 * The single pseudonymous installation identifier used for every AegisHub report.
 *
 * Session launches, crash reports and feedback must all agree on `user_reference`,
 * otherwise the console counts one installation as several users. The value is
 * generated once on first use and persisted synchronously so a crash right after the
 * first launch cannot produce a second identity.
 */
object AegisIdentity {
    private const val TAG = "AegisIdentity"
    private const val PREFS_NAME = "aegis_identity"
    private const val KEY_INSTALL_ID = "install_id"

    private val lock = Any()

    fun installId(context: Context): String {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_INSTALL_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        return synchronized(lock) {
            prefs.getString(KEY_INSTALL_ID, null)?.takeIf { it.isNotBlank() }
                ?: run {
                    val generated = UUID.randomUUID().toString()
                    val committed = prefs.edit()
                        .putString(KEY_INSTALL_ID, generated)
                        .commit()
                    if (!committed) {
                        Timber.tag(TAG).w("Failed to persist install id")
                    }
                    generated
                }
        }
    }

    /** Only for tests. */
    internal fun resetForTesting(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }
}
