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
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
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

private class PluginNetworkResponseTooLargeException(maxBytes: Int) : java.io.IOException(
    "Network response exceeds $maxBytes bytes",
)

/** Host-owned persistent storage and network capabilities. */
internal class PluginHostDataCapabilities(
    context: Context,
    private val pluginManager: PluginManager,
    private val hasAnyPermission: (PluginManifest, Set<PluginPermission>) -> Boolean,
    private val networkClient: OkHttpClient = createNetworkClient(),
) {
    companion object {
        private const val STORAGE_PREFERENCES_NAME = "plugin_storage"
        private const val MAX_NETWORK_BODY_BYTES = 8 * 1024 * 1024
        private const val MAX_STORAGE_VALUE_BYTES = 64 * 1024
        private const val MAX_STORAGE_TOTAL_BYTES = 1024 * 1024
        private const val MAX_STORAGE_KEYS = 128
        private const val MAX_STORAGE_WRITES_PER_MINUTE = 120
        private const val MAX_DATABASE_CALLS_PER_MINUTE = 300
        private val STORAGE_LOCK = Any()

        internal fun clearPersistentData(context: Context, pluginId: String) {
            val preferences = context.getSharedPreferences(STORAGE_PREFERENCES_NAME, Context.MODE_PRIVATE)
            synchronized(STORAGE_LOCK) {
                val prefix = "$pluginId:"
                val editor = preferences.edit()
                preferences.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
                check(editor.commit()) { "Failed to clear plugin storage" }
            }
            PluginDatabase.deletePersistentFiles(context, pluginId)
        }
    }

    private val storagePreferences = context.getSharedPreferences(STORAGE_PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val databases = ConcurrentHashMap<String, PluginDatabase>()
    private val networkLimiters = ConcurrentHashMap<String, RateLimiter>()
    private val storageWriteLimiters = ConcurrentHashMap<String, RateLimiter>()
    private val databaseLimiters = ConcurrentHashMap<String, RateLimiter>()
    private val appContext = context.applicationContext

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
        storageWriteLimiters.remove(pluginId)
        databaseLimiters.remove(pluginId)
    }

    fun cleanup() {
        databases.values.forEach(PluginDatabase::close)
        databases.clear()
        networkLimiters.clear()
        storageWriteLimiters.clear()
        databaseLimiters.clear()
    }

    private fun handleStorage(request: PluginHostCallRequest): PluginHostCallResponse {
        val key = request.args.string(0) ?: return hostSuccess(JsonNull)
        if (key.length > 256) {
            return hostFailureWithValues(
                JsonNull,
                "Storage key is too long",
                PluginHostErrorKind.INVALID_REQUEST,
            )
        }
        val fullKey = "${request.pluginId}:$key"
        return when (request.method) {
            "get" -> hostSuccess(storagePreferences.getString(fullKey, null)?.let(::JsonPrimitive) ?: JsonNull)
            "set" -> {
                if (!storageWriteLimiter(request.pluginId).tryAcquire()) {
                    return hostFailureWithValues(JsonNull, "Plugin storage write rate limit exceeded")
                }
                val value = request.args.string(1).orEmpty()
                val valueBytes = value.toByteArray(StandardCharsets.UTF_8).size
                if (valueBytes > MAX_STORAGE_VALUE_BYTES) {
                    return hostFailureWithValues(JsonNull, "Plugin storage value exceeds the size limit")
                }
                synchronized(STORAGE_LOCK) {
                    val prefix = "${request.pluginId}:"
                    val currentValues = storagePreferences.all
                        .filterKeys { it.startsWith(prefix) }
                        .mapValues { it.value as? String ?: "" }
                    val keyCount = currentValues.size + if (fullKey in currentValues) 0 else 1
                    val totalBytes = currentValues
                        .filterKeys { it != fullKey }
                        .values
                        .sumOf { it.toByteArray(StandardCharsets.UTF_8).size } + valueBytes
                    if (keyCount > MAX_STORAGE_KEYS || totalBytes > MAX_STORAGE_TOTAL_BYTES) {
                        return hostFailureWithValues(JsonNull, "Plugin storage quota exceeded")
                    }
                    if (!storagePreferences.edit().putString(fullKey, value).commit()) {
                        return hostFailureWithValues(JsonNull, "Failed to persist plugin storage")
                    }
                }
                hostSuccess()
            }
            "remove" -> {
                if (!storageWriteLimiter(request.pluginId).tryAcquire()) {
                    return hostFailureWithValues(JsonNull, "Plugin storage write rate limit exceeded")
                }
                synchronized(STORAGE_LOCK) {
                    if (!storagePreferences.edit().remove(fullKey).commit()) {
                        return hostFailureWithValues(JsonNull, "Failed to persist plugin storage")
                    }
                }
                hostSuccess()
            }
            else -> hostInvalidMethod(request)
        }
    }

    private fun storageWriteLimiter(pluginId: String): RateLimiter = storageWriteLimiters.computeIfAbsent(pluginId) {
        RateLimiter(MAX_STORAGE_WRITES_PER_MINUTE, 60_000L)
    }

    private fun handleNetwork(request: PluginHostCallRequest): PluginHostCallResponse {
        val method = when (request.method) {
            "get" -> "GET"
            "post" -> "POST"
            "fetch" -> request.args.string(1)?.uppercase(Locale.ROOT) ?: "GET"
            else -> return hostInvalidMethod(request)
        }
        if (method !in setOf("GET", "POST", "PUT", "DELETE", "PATCH")) {
            return hostFailureWithValues(
                JsonNull,
                "Unsupported HTTP method: $method",
                PluginHostErrorKind.INVALID_REQUEST,
            )
        }
        val urlValue = request.args.string(0)
            ?: return hostFailureWithValues(JsonNull, "URL is required", PluginHostErrorKind.INVALID_REQUEST)
        val url = urlValue.toHttpUrlOrNull()
            ?: return hostFailureWithValues(JsonNull, "URL is invalid", PluginHostErrorKind.INVALID_REQUEST)
        if (!networkLimiter(request.pluginId).tryAcquire()) {
            return hostFailureWithValues(JsonNull, "Network request rate limit exceeded")
        }
        val manifest = pluginManager.getInstalledPlugin(request.pluginId)?.manifest
            ?: return hostFailure("Plugin manifest unavailable", PluginHostErrorKind.HOST_UNAVAILABLE)
        val unrestricted = hasAnyPermission(manifest, setOf(PluginPermission.NETWORK_UNRESTRICTED))
        if (!unrestricted && !PluginNetworkHostRules.isUrlAllowed(url.toString(), manifest.networkHosts.orEmpty())) {
            return hostFailureWithValues(
                JsonNull,
                "Host not in whitelist: ${url.host}",
                PluginHostErrorKind.PERMISSION_DENIED,
            )
        }
        val body = if (request.method == "fetch") request.args.string(2) else request.args.string(1)
        val contentType = if (request.method == "fetch") request.args.string(3) else request.args.string(2)
        if (body != null && body.toByteArray(StandardCharsets.UTF_8).size > MAX_NETWORK_BODY_BYTES) {
            return hostFailureWithValues(
                JsonNull,
                "Network request body exceeds the size limit",
                PluginHostErrorKind.INVALID_REQUEST,
            )
        }
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
        }
        try {
            networkClient.newCall(requestBuilder.build()).execute().use { response ->
                val responseBody = response.body
                if (responseBody != null && responseBody.contentLength() > MAX_NETWORK_BODY_BYTES) {
                    return hostFailureWithValues(JsonNull, "Network response exceeds the size limit")
                }
                val responseText = responseBody?.byteStream()?.use { input ->
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
        } catch (_: PluginNetworkResponseTooLargeException) {
            return hostFailureWithValues(JsonNull, "Network response exceeds the size limit")
        }
    }

    private fun handleDatabase(request: PluginHostCallRequest): PluginHostCallResponse {
        if (!databaseLimiter(request.pluginId).tryAcquire()) {
            return hostFailure("Plugin database call rate limit exceeded")
        }
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

    private fun databaseLimiter(pluginId: String): RateLimiter =
        databaseLimiters.computeIfAbsent(pluginId) { RateLimiter(MAX_DATABASE_CALLS_PER_MINUTE, 60_000L) }

    private fun InputStream.readLimited(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw PluginNetworkResponseTooLargeException(maxBytes)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun JsonArray.string(index: Int): String? = getOrNull(index)
        ?.takeUnless { it is JsonNull }
        ?.let { it as? JsonPrimitive }
        ?.contentOrNull
}

private fun createNetworkClient(): OkHttpClient = OkHttpClientProvider.custom {
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
