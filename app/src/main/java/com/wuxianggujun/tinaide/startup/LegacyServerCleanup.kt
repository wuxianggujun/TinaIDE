package com.wuxianggujun.tinaide.startup

import android.content.Context
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Remove persisted jobs and endpoint caches when upgrading from the retired backend. */
internal object LegacyServerCleanup {
    private const val KEY_COMPLETED = "migration.legacy_server_removed"

    suspend fun run(context: Context) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("tinaide_config", Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_COMPLETED, false)) return@withContext
        try {
            val workManager = WorkManager.getInstance(context)
            listOf("server_config_sync", "favorite_sync_work").forEach { name ->
                workManager.cancelUniqueWork(name).result.get(15, TimeUnit.SECONDS)
            }
            // Local favorites/download history are Room data and are preserved.
            listOf("tina_server_config", "server_config").forEach { name ->
                check(context.deleteSharedPreferences(name)) { "Failed to remove legacy preferences: $name" }
            }
            check(prefs.edit().putBoolean(KEY_COMPLETED, true).commit()) {
                "Failed to persist legacy server cleanup"
            }
            Timber.tag("LegacyServerCleanup").i("Retired server jobs and configuration removed")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag("LegacyServerCleanup").w(e, "Legacy server cleanup deferred until next startup")
        }
    }
}
