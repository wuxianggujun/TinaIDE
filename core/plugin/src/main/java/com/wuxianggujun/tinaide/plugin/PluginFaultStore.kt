package com.wuxianggujun.tinaide.plugin

import android.content.Context
import android.content.SharedPreferences
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class PluginFaultRecord(
    val pluginId: String,
    val pluginVersion: String,
    val phase: PluginFaultPhase,
    val kind: PluginFaultKind,
    val message: String,
    val timestampMillis: Long,
    val executionId: String,
)

@Serializable
enum class PluginFaultPhase {
    STARTUP,
    COMMAND,
    EVENT,
    API_CALL,
    LSP,
    CONTRIBUTION,
    UNKNOWN,
}

@Serializable
enum class PluginFaultKind {
    STARTUP_EXCEPTION,
    UNHANDLED_EXCEPTION,
    EXECUTION_TIMEOUT,
    RUNTIME_CRASH,
    RESOURCE_LIMIT,
    LSP_CRASH,
    INVALID_CONTRIBUTION,
    INTERRUPTED_EXECUTION,
}

@Serializable
enum class PluginEffectiveStatus {
    DISABLED,
    WAITING_PERMISSION,
    LOADING,
    ACTIVE,
    QUARANTINED,
    RUNTIME_UNAVAILABLE,
}

@Serializable
data class PluginInFlightRecord(
    val pluginId: String,
    val pluginVersion: String,
    val generation: Long,
    val phase: PluginFaultPhase,
    val executionId: String,
    val startedAtMillis: Long,
)

/**
 * Crash-loop-safe plugin state journal.
 *
 * Critical writes use commit(): the record must reach disk before untrusted code runs.
 */
class PluginFaultStore private constructor(context: Context) {
    companion object {
        private const val PREFS_NAME = "tinaide_plugin_runtime_state"
        private const val KEY_FAULTS = "faults"
        private const val KEY_IN_FLIGHT = "in_flight"
        private const val KEY_EFFECTIVE_STATUSES = "effective_statuses"
        private const val MAX_MESSAGE_LENGTH = 2_048

        private val instanceRef = AtomicReference<PluginFaultStore?>()

        fun getInstance(context: Context): PluginFaultStore = instanceRef.get()
            ?: synchronized(this) {
                instanceRef.get() ?: PluginFaultStore(context.applicationContext).also(instanceRef::set)
            }

        internal fun resetForTests() {
            instanceRef.set(null)
        }
    }

    private val json = JsonSerializer.default
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val stateLock = Any()
    private val _faults = MutableStateFlow(loadFaults())
    val faults: StateFlow<Map<String, PluginFaultRecord>> = _faults.asStateFlow()
    private val _effectiveStatuses = MutableStateFlow(loadEffectiveStatuses())
    val effectiveStatuses: StateFlow<Map<String, PluginEffectiveStatus>> = _effectiveStatuses.asStateFlow()

    fun getFault(pluginId: String): PluginFaultRecord? = _faults.value[pluginId]

    fun isQuarantined(pluginId: String): Boolean = getFault(pluginId) != null

    fun recordFault(record: PluginFaultRecord): Boolean = synchronized(stateLock) {
        val sanitized = record.copy(message = sanitizeMessage(record.message))
        val updatedFaults = _faults.value + (record.pluginId to sanitized)
        val updatedStatuses = _effectiveStatuses.value + (record.pluginId to PluginEffectiveStatus.QUARANTINED)
        val persisted = prefs.edit()
            .putString(KEY_FAULTS, json.encodeToString(updatedFaults))
            .putString(KEY_EFFECTIVE_STATUSES, json.encodeToString(updatedStatuses))
            .commit()
        if (persisted) {
            _faults.update { current -> current + (record.pluginId to sanitized) }
            _effectiveStatuses.update { current ->
                current + (record.pluginId to PluginEffectiveStatus.QUARANTINED)
            }
        }
        persisted
    }

    fun clearFault(pluginId: String): Boolean = synchronized(stateLock) {
        if (pluginId !in _faults.value) return@synchronized true
        val updated = _faults.value - pluginId
        val persisted = prefs.edit().putString(KEY_FAULTS, json.encodeToString(updated)).commit()
        if (persisted) _faults.update { current -> current - pluginId }
        persisted
    }

    fun setEffectiveStatus(pluginId: String, status: PluginEffectiveStatus): Boolean = synchronized(stateLock) {
        if (_effectiveStatuses.value[pluginId] == status) return@synchronized true
        val updated = _effectiveStatuses.value + (pluginId to status)
        val persisted = prefs.edit().putString(KEY_EFFECTIVE_STATUSES, json.encodeToString(updated)).commit()
        if (persisted) _effectiveStatuses.update { current -> current + (pluginId to status) }
        persisted
    }

    fun getEffectiveStatus(pluginId: String): PluginEffectiveStatus? = _effectiveStatuses.value[pluginId]

    fun clearAllForUninstall(pluginId: String): Boolean = synchronized(stateLock) {
        val updatedFaults = _faults.value - pluginId
        val updatedStatuses = _effectiveStatuses.value - pluginId
        val inFlight = getInFlightLocked()
        val editor = prefs.edit()
            .putString(KEY_FAULTS, json.encodeToString(updatedFaults))
            .putString(KEY_EFFECTIVE_STATUSES, json.encodeToString(updatedStatuses))
        if (inFlight?.pluginId == pluginId) editor.remove(KEY_IN_FLIGHT)
        val persisted = editor.commit()
        if (persisted) {
            _faults.update { current -> current - pluginId }
            _effectiveStatuses.update { current -> current - pluginId }
        }
        persisted
    }

    fun beginExecution(record: PluginInFlightRecord): Boolean = synchronized(stateLock) {
        if (getInFlightLocked() != null) return@synchronized false
        prefs.edit()
            .putString(KEY_IN_FLIGHT, json.encodeToString(record))
            .commit()
    }

    fun getInFlight(): PluginInFlightRecord? = synchronized(stateLock) {
        getInFlightLocked()
    }

    fun clearInFlight(executionId: String): Boolean = synchronized(stateLock) {
        val current = getInFlightLocked() ?: return@synchronized true
        if (current.executionId != executionId) return@synchronized false
        prefs.edit().remove(KEY_IN_FLIGHT).commit()
    }

    private fun getInFlightLocked(): PluginInFlightRecord? = prefs.getString(KEY_IN_FLIGHT, null)
        ?.let { encoded -> json.decodeFromString<PluginInFlightRecord>(encoded) }

    private fun loadFaults(): Map<String, PluginFaultRecord> = prefs.getString(KEY_FAULTS, null)
        ?.let { encoded ->
            runCatching { json.decodeFromString<Map<String, PluginFaultRecord>>(encoded) }.getOrDefault(emptyMap())
        }
        ?: emptyMap()

    private fun loadEffectiveStatuses(): Map<String, PluginEffectiveStatus> =
        prefs.getString(KEY_EFFECTIVE_STATUSES, null)
            ?.let { encoded ->
                runCatching { json.decodeFromString<Map<String, PluginEffectiveStatus>>(encoded) }
                    .getOrDefault(emptyMap())
            }
            ?: emptyMap()

    private fun sanitizeMessage(message: String): String = message
        .replace(Regex("/data/(?:data|user/\\d+)/[^/]+"), "<app-data>")
        .replace(Regex("(?i)(token|api[_-]?key|authorization)\\s*[:=]\\s*[^\\s,;]+"), "$1=<redacted>")
        .take(MAX_MESSAGE_LENGTH)
}
