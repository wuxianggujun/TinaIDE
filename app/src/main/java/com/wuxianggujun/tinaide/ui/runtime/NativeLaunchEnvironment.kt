package com.wuxianggujun.tinaide.ui.runtime

import android.content.Intent
import android.os.Bundle
import android.system.Os
import com.wuxianggujun.tinaide.core.compile.LaunchEnvironment
import timber.log.Timber

/**
 * 管理图形运行宿主启动前对当前 App 进程环境变量的临时注入。
 *
 * 环境变量是进程级全局状态，因此需要在启动结束后恢复，避免污染后续启动。
 */
object NativeLaunchEnvironment {
    private const val TAG = "NativeLaunchEnv"
    private const val EXTRA_LAUNCH_ENVIRONMENT = "extra_launch_environment"

    private val lock = Any()
    private var activeOwnerId: String? = null
    private val originalValues = linkedMapOf<String, String?>()

    fun putIntoIntent(intent: Intent, environment: Map<String, String>) {
        val normalized = LaunchEnvironment.sanitized(environment)
        if (normalized.isEmpty()) return
        val bundle = Bundle(normalized.size)
        normalized.forEach { (key, value) -> bundle.putString(key, value) }
        intent.putExtra(EXTRA_LAUNCH_ENVIRONMENT, bundle)
    }

    fun readFromIntent(intent: Intent): Map<String, String> {
        val bundle = intent.getBundleExtra(EXTRA_LAUNCH_ENVIRONMENT) ?: return emptyMap()
        val rawEnvironment = buildMap {
            bundle.keySet().forEach { key ->
                bundle.getString(key)?.let { value -> put(key, value) }
            }
        }
        return LaunchEnvironment.sanitized(rawEnvironment)
    }

    fun apply(ownerId: String, environment: Map<String, String>) {
        val normalized = LaunchEnvironment.sanitized(environment)
        synchronized(lock) {
            if (activeOwnerId != null && activeOwnerId != ownerId) {
                restoreLocked()
            }
            activeOwnerId = ownerId
            reconcileLocked(normalized)
            if (normalized.isEmpty()) {
                activeOwnerId = null
            }
        }
    }

    fun clear(ownerId: String) {
        synchronized(lock) {
            if (activeOwnerId != ownerId) return
            restoreLocked()
            activeOwnerId = null
        }
    }

    private fun reconcileLocked(environment: Map<String, String>) {
        val staleKeys = originalValues.keys - environment.keys
        staleKeys.forEach { key ->
            restoreKeyLocked(key)
            originalValues.remove(key)
        }
        environment.forEach { (key, value) ->
            originalValues.putIfAbsent(key, System.getenv(key))
            runCatching { Os.setenv(key, value, true) }
                .onFailure { throwable ->
                    Timber.tag(TAG).w(throwable, "Failed to apply launch environment key: %s", key)
                }
        }
    }

    private fun restoreLocked() {
        val managedKeys = originalValues.keys.toList()
        managedKeys.forEach(::restoreKeyLocked)
        originalValues.clear()
    }

    private fun restoreKeyLocked(key: String) {
        val previousValue = originalValues[key]
        val result = if (previousValue == null) {
            runCatching { Os.unsetenv(key) }
        } else {
            runCatching { Os.setenv(key, previousValue, true) }
        }
        result.onFailure { throwable ->
            Timber.tag(TAG).w(throwable, "Failed to restore launch environment key: %s", key)
        }
    }
}
