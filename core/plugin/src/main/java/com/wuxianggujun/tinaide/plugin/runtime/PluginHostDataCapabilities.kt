package com.wuxianggujun.tinaide.plugin.runtime

import android.content.Context
import com.wuxianggujun.tinaide.core.network.OkHttpClientProvider
import com.wuxianggujun.tinaide.plugin.PluginManager
import com.wuxianggujun.tinaide.plugin.PluginManifest
import com.wuxianggujun.tinaide.plugin.PluginNetworkHostRules
import com.wuxianggujun.tinaide.plugin.script.PluginPermission
import com.wuxianggujun.tinaide.plugin.script.RateLimiter
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal data class PluginNetworkRequestPolicy(
    val unrestricted: Boolean,
    val allowedHosts: Set<String>,
) {
    fun allows(url: HttpUrl): Boolean = unrestricted ||
        PluginNetworkHostRules.isUrlAllowed(url.toString(), allowedHosts)
}

private class PluginNetworkPolicyException(val blockedHost: String?) : java.io.IOException(
    "Plugin network redirect target is not allowed",
)

/** Host-owned persistent storage and network capabilities. */
internal class PluginHostDataCapabilities(
    context: Context,
    private val pluginManager: PluginManager,
    private val hasAnyPermission: (PluginManifest, Set<PluginPermission>) -> Boolean,
) {
    companion object {
        private const val STORAGE_PREFERENCES_NAME = "plugin_storage"
        private const val MAX_NETWORK_BODY_BYTES = 8 * 1024 * 1024

        internal fun clearPersistentData(context: Context, pluginId: String) {
            val preferences = context.getSharedPreferences(STORAGE_PREFERENCES_NAME, Context.MODE_PRIVATE)
            val prefix = "$pluginId:"
            val editor = preferences.edit()
            preferences.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
            check(editor.commit()) { "Failed to clear plugin storage" }
            PluginDatabase.deletePersistentFiles(context, pluginId)
        }
    }

    private val storagePreferences = context.getSharedPreferences(STORAGE_PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val databases = ConcurrentHashMap<String, PluginDatabase>()
    private val networkLimiters = ConcurrentHashMap<String, RateLimiter>()
    private val appContext = context.applicationContext
    private val networkClient = OkHttpClientProvider.custom {
        addNetworkInterceptor(
            Interceptor { chain ->
                val request = chain.request()
                val policy = request.tag(PluginNetworkRequestPolicy::class.java)
                if (policy != null && !policy.allows(request.url)) {
                    throw PluginNetworkPolicyException(request.url.host)
                }
                chain.proceed(request)
            },
        )
    }

    fun call(request: PluginHostCallRequest): PluginHostCallResponse = when (request.namespace) {
        "storage" -> handleStorage(request)
        "network" -> handleNetwork(request)
        "db" -> handleDatabase(request)
        else -> hostFailure(
            "Unknown data capability namespace: ${request.namespace}",
            PluginHostErrorKind.INVALID_REQUEST,
        )
    }

    fun cleanupPlugin(pluginId: String) {
        databases.remove(pluginId)?.close()
        networkLimiters.remove(pluginId)
    }

    fun cleanup() {
        databases.values.forEach(PluginDatabase::close)
        databases.clear()
        networkLimiters.clear()
    }

    private fun handleStorage(request: PluginHostCallRequest): PluginHostCallResponse {
        val key = request.args.string(0) ?: return hostSuccess(JsonNull)
        require(key.length <= 256) { "Storage key is too long" }
        val fullKey = "${request.pluginId}:$key"
        return when (request.method) {
            "get" -> hostSuccess(storagePreferences.getString(fullKey, null)?.let(::JsonPrimitive) ?: JsonNull)
            "set" -> {
                storagePreferences.edit().putString(fullKey, request.args.string(1)).apply()
                hostSuccess()
            }
            "remove" -> {
                storagePreferences.edit().remove(fullKey).apply()
                hostSuccess()
            }
            else -> hostInvalidMethod(request)
        }
    }

    private fun handleNetwork(request: PluginHostCallRequest): PluginHostCallResponse {
        if (!networkLimiter(request.pluginId).tryAcquire()) {
            return hostFailureWithValues(JsonNull, "Network request rate limit exceeded")
        }
        val url = request.args.string(0) ?: return hostFailureWithValues(JsonNull, "URL is required")
        val manifest = pluginManager.getInstalledPlugin(request.pluginId)?.manifest
            ?: return hostFailure("Plugin manifest unavailable", PluginHostErrorKind.HOST_UNAVAILABLE)
        val unrestricted = hasAnyPermission(manifest, setOf(PluginPermission.NETWORK_UNRESTRICTED))
        if (!unrestricted && !PluginNetworkHostRules.isUrlAllowed(url, manifest.networkHosts.orEmpty())) {
            return hostFailureWithValues(
                JsonNull,
                "Host not in whitelist: ${PluginNetworkHostRules.extractRequestHost(url)}",
            )
        }
        val method = when (request.method) {
            "get" -> "GET"
            "post" -> "POST"
            "fetch" -> request.args.string(1)?.uppercase() ?: "GET"
            else -> return hostInvalidMethod(request)
        }
        val body = if (request.method == "fetch") request.args.string(2) else request.args.string(1)
        val contentType = if (request.method == "fetch") request.args.string(3) else request.args.string(2)
        val networkPolicy = PluginNetworkRequestPolicy(
            unrestricted = unrestricted,
            allowedHosts = manifest.networkHosts.orEmpty().toSet(),
        )
        val requestBuilder = Request.Builder()
            .url(url)
            .tag(PluginNetworkRequestPolicy::class.java, networkPolicy)
        val requestBody = (body ?: "").toRequestBody((contentType ?: "application/json").toMediaTypeOrNull())
        when (method) {
            "GET" -> requestBuilder.get()
            "POST" -> requestBuilder.post(requestBody)
            "PUT" -> requestBuilder.put(requestBody)
            "DELETE" -> requestBuilder.delete()
            "PATCH" -> requestBuilder.patch(requestBody)
            else -> return hostFailureWithValues(JsonNull, "Unsupported HTTP method: $method")
        }
        try {
            networkClient.newCall(requestBuilder.build()).execute().use { response ->
                val responseText = response.body?.byteStream()?.use { input ->
                    String(input.readLimited(MAX_NETWORK_BODY_BYTES), StandardCharsets.UTF_8)
                }.orEmpty()
                return when (request.method) {
                    "fetch" -> hostSuccess(
                        JsonObject(
                            mapOf(
                                "status" to JsonPrimitive(response.code),
                                "ok" to JsonPrimitive(response.isSuccessful),
                                "body" to JsonPrimitive(responseText),
                                "headers" to JsonObject(
                                    response.headers.associate { (name, value) -> name to JsonPrimitive(value) },
                                ),
                            ),
                        ),
                    )
                    else -> hostSuccess(JsonPrimitive(responseText))
                }
            }
        } catch (error: PluginNetworkPolicyException) {
            return hostFailureWithValues(
                JsonNull,
                "Host not in whitelist: ${error.blockedHost.orEmpty()}",
                PluginHostErrorKind.PERMISSION_DENIED,
            )
        }
    }

    private fun handleDatabase(request: PluginHostCallRequest): PluginHostCallResponse {
        val database = databases.computeIfAbsent(request.pluginId) { PluginDatabase(appContext, request.pluginId) }
        return when (request.method) {
            "execute" -> {
                val sql = request.args.string(0)
                if (!PluginSqlPolicy.isAllowedMutation(sql)) {
                    hostFailureWithValues(
                        JsonPrimitive(-1),
                        "SQL statement is not allowed",
                        PluginHostErrorKind.INVALID_REQUEST,
                    )
                } else {
                    hostSuccess(JsonPrimitive(database.execute(checkNotNull(sql), request.args.getOrNull(1))))
                }
            }
            "query" -> {
                val sql = request.args.string(0)
                if (!PluginSqlPolicy.isAllowedQuery(sql)) {
                    hostFailureWithValues(
                        JsonArray(emptyList()),
                        "SQL query is not allowed",
                        PluginHostErrorKind.INVALID_REQUEST,
                    )
                } else {
                    hostSuccess(JsonArray(database.query(checkNotNull(sql), request.args.getOrNull(1))))
                }
            }
            "tableExists" -> hostSuccess(JsonPrimitive(database.tableExists(request.args.string(0))))
            "__beginTransaction" -> {
                database.beginTransaction()
                hostSuccess()
            }
            "__commitTransaction" -> {
                database.commitTransaction()
                hostSuccess()
            }
            "__rollbackTransaction" -> {
                database.rollbackTransaction()
                hostSuccess()
            }
            "close" -> {
                databases.remove(request.pluginId)?.close()
                hostSuccess()
            }
            else -> hostInvalidMethod(request)
        }
    }

    private fun networkLimiter(pluginId: String): RateLimiter =
        networkLimiters.computeIfAbsent(pluginId) { RateLimiter(30, 60_000L) }

    private fun InputStream.readLimited(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "Response exceeds $maxBytes bytes" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun JsonArray.string(index: Int): String? = getOrNull(index)
        ?.takeUnless { it is JsonNull }
        ?.jsonPrimitive
        ?.contentOrNull
}
