package com.wuxianggujun.tinaide.plugin.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray

internal const val MAX_BINDER_JSON_BYTES: Int = 256 * 1024
internal const val MAX_LUA_SOURCE_BYTES: Int = 1024 * 1024
internal const val MAX_LUA_SOURCE_TOTAL_BYTES: Long = 8L * 1024L * 1024L
internal const val DEFAULT_PLUGIN_EXECUTION_TIMEOUT_MS: Long = 5_000L
internal const val MAX_PLUGIN_CALL_DURATION_MS: Long = 60_000L

internal class PluginRuntimePayloadTooLargeException(
    label: String,
) : IllegalArgumentException("$label exceeds $MAX_BINDER_JSON_BYTES bytes")

@Serializable
internal data class PluginRuntimeLoadRequest(
    val pluginId: String,
    val pluginName: String,
    val version: String,
    val apiVersion: Int,
    val generation: Long,
    val callId: String,
)

@Serializable
internal data class PluginRuntimeInvokeRequest(
    val pluginId: String,
    val generation: Long,
    val callId: String,
    val functionName: String,
    val args: JsonArray = JsonArray(emptyList()),
)

@Serializable
internal data class PluginRuntimeUnloadRequest(
    val pluginId: String,
    val generation: Long,
    val callId: String,
)

@Serializable
internal data class PluginRuntimeResponse(
    val pluginId: String,
    val generation: Long,
    val callId: String,
    val status: PluginRuntimeResponseStatus,
    val values: JsonArray = JsonArray(emptyList()),
    val error: String? = null,
    val stack: String? = null,
)

@Serializable
internal enum class PluginRuntimeResponseStatus {
    SUCCESS,
    PLUGIN_ERROR,
    RUNTIME_ERROR,
    RESOURCE_LIMIT,
    TIMEOUT,
    STALE_GENERATION,
}

@Serializable
internal data class PluginHostCallRequest(
    val pluginId: String,
    val generation: Long,
    val namespace: String,
    val method: String,
    val args: JsonArray = JsonArray(emptyList()),
)

@Serializable
internal data class PluginHostCallResponse(
    val values: JsonArray = JsonArray(emptyList()),
    val bulkValues: Map<Int, PluginBulkPayloadRef> = emptyMap(),
    val errorKind: PluginHostErrorKind? = null,
    val error: String? = null,
)

@Serializable
internal data class PluginBulkPayloadRef(
    val token: String,
    val sizeBytes: Long,
    val encoding: PluginBulkPayloadEncoding = PluginBulkPayloadEncoding.STRING,
)

@Serializable
internal enum class PluginBulkPayloadEncoding {
    STRING,
    JSON,
}

@Serializable
internal enum class PluginHostErrorKind {
    INVALID_REQUEST,
    PERMISSION_DENIED,
    HOST_UNAVAILABLE,
    BUSINESS_ERROR,
}

@Serializable
internal data class PluginLuaModuleRequest(
    val pluginId: String,
    val generation: Long,
    val moduleName: String,
)
