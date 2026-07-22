package com.wuxianggujun.tinaide.core.network.server

import com.wuxianggujun.tinaide.core.device.DeviceInfo
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str
import com.wuxianggujun.tinaide.core.network.ApiEnvelopeParseResult
import com.wuxianggujun.tinaide.core.network.ApiEnvelopeParser
import com.wuxianggujun.tinaide.core.network.ApiResult
import com.wuxianggujun.tinaide.core.network.OkHttpClientProvider
import com.wuxianggujun.tinaide.core.security.ServerConfigHmacVerifier
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import com.wuxianggujun.tinaide.core.serialization.MessagePackCodec
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

class TinaServerApi private constructor(
    private val baseUrl: String,
    private val client: OkHttpClient
) {
    private val json = JsonSerializer.default

    companion object {
        private const val TAG = "TinaServerApi"
        private const val SERVER_CONFIG_PROTOCOL_VERSION = 1
        private const val SERVER_CONFIG_MAX_CLOCK_SKEW_SECS = 24 * 60 * 60

        @Volatile
        private var instance: TinaServerApi? = null

        fun getInstance(
            baseUrl: String,
            client: OkHttpClient = OkHttpClientProvider.default
        ): TinaServerApi = instance ?: synchronized(this) {
            instance ?: TinaServerApi(baseUrl.trimEnd('/'), client).also { instance = it }
        }

        fun resetInstance() {
            instance = null
        }
    }

    suspend fun uploadLog(
        logType: String,
        deviceFingerprint: String,
        deviceInfo: DeviceInfo,
        title: String? = null,
        content: String? = null,
        file: File? = null,
        extraFields: Map<String, String> = emptyMap(),
    ): ApiResult<LogUploadResponse> {
        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("log_type", logType)
            .addFormDataPart("device_fingerprint", deviceFingerprint)
            .addFormDataPart("device_info", json.encodeToString(deviceInfo))
            .addFormDataPart("device_model", deviceInfo.model)
            .addFormDataPart("device_brand", deviceInfo.model.split(" ").firstOrNull() ?: "Unknown")
            .addFormDataPart("android_version", deviceInfo.osVersion)

        title?.takeIf { it.isNotBlank() }?.let { form.addFormDataPart("title", it) }
        content?.takeIf { it.isNotBlank() }?.let { form.addFormDataPart("content", it) }

        extraFields.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank()) {
                form.addFormDataPart(key, value)
            }
        }

        file?.let { f ->
            form.addFormDataPart("file", f.name, f.asRequestBody(guessLogMediaType(f.name)))
        }

        return postMultipart("/api/logs/upload", form.build())
    }

    suspend fun getServerConfig(): ApiResult<ServerConfigResponse> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/server-config")
                .get()
                .addHeader("Accept", "application/msgpack")
                .build()

            val response = client.newCall(request).execute()
            val bytes = response.body?.bytes()

            if (bytes == null || bytes.isEmpty()) {
                return@withContext if (response.isSuccessful) {
                    ApiResult.Error(-1, Strings.error_response_empty.str())
                } else {
                    ApiResult.Error(
                        code = response.code,
                        message = Strings.error_request_failed_with_code.str(response.code)
                    )
                }
            }

            if (!response.isSuccessful) {
                val bodyText = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
                val errorMessage = parseErrorMessage(bodyText)
                return@withContext ApiResult.Error(
                    code = response.code,
                    message = errorMessage ?: Strings.error_request_failed_with_code.str(response.code)
                )
            }

            return@withContext runCatching {
                val payload = MessagePackCodec.decodeOkEnvelope<ServerConfigPayload>(bytes)
                if (payload.protocolVersion != SERVER_CONFIG_PROTOCOL_VERSION) {
                    error("Unsupported protocol_version=${payload.protocolVersion}")
                }

                val nowSecs = System.currentTimeMillis() / 1000
                if (abs(nowSecs - payload.timestamp) > SERVER_CONFIG_MAX_CLOCK_SKEW_SECS) {
                    error("Timestamp out of range: now=$nowSecs, ts=${payload.timestamp}")
                }

                val secret = TinaServerEnvironment.serverConfigHmacSecret
                if (secret.isNotBlank()) {
                    val ok = ServerConfigHmacVerifier.verify(
                        secret = secret.toByteArray(),
                        protocolVersion = payload.protocolVersion,
                        timestamp = payload.timestamp,
                        data = payload.data,
                        signature = payload.signature
                    )
                    if (!ok) error("Invalid signature")
                } else if (TinaServerEnvironment.serverConfigSignatureRequired) {
                    error("Server config signature verification is required")
                } else {
                    Timber.tag(TAG).w("SERVER_CONFIG_HMAC_SECRET is empty; skip signature verification")
                }

                ApiResult.Success(MessagePackCodec.decode<ServerConfigResponse>(payload.data))
            }.getOrElse {
                Timber.tag(TAG).e(it, "Failed to parse msgpack server-config response")
                ApiResult.Error(-1, Strings.error_response_parse_failed.str())
            }
        } catch (e: IOException) {
            Timber.tag(TAG).e(e, "GET /api/server-config network error")
            ApiResult.NetworkError(e.message ?: Strings.error_network_connection_failed.str())
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "GET /api/server-config unknown error")
            ApiResult.Error(-1, e.message ?: Strings.error_unknown.str())
        }
    }

    suspend fun healthCheck(): ApiResult<HealthResponse> = get("/health", expectEnvelope = false)

    private suspend inline fun <reified T> get(
        path: String,
        expectEnvelope: Boolean = true
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        try {
            val response = client.newCall(
                Request.Builder()
                    .url("$baseUrl$path")
                    .get()
                    .build()
            ).execute()
            parseResponse(response, expectEnvelope)
        } catch (e: IOException) {
            Timber.tag(TAG).e(e, "GET $path network error")
            ApiResult.NetworkError(e.message ?: Strings.error_network_connection_failed.str())
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "GET $path unknown error")
            ApiResult.Error(-1, e.message ?: Strings.error_unknown.str())
        }
    }

    private suspend inline fun <reified T, reified B : Any> post(
        path: String,
        body: B,
        expectEnvelope: Boolean = true
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        try {
            val response = client.newCall(
                Request.Builder()
                    .url("$baseUrl$path")
                    .post(json.encodeToString<B>(body).toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute()
            parseResponse(response, expectEnvelope)
        } catch (e: IOException) {
            Timber.tag(TAG).e(e, "POST $path network error")
            ApiResult.NetworkError(e.message ?: Strings.error_network_connection_failed.str())
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "POST $path unknown error")
            ApiResult.Error(-1, e.message ?: Strings.error_unknown.str())
        }
    }

    private suspend inline fun <reified T> postMultipart(
        path: String,
        body: okhttp3.RequestBody,
        expectEnvelope: Boolean = true
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        try {
            val response = client.newCall(
                Request.Builder()
                    .url("$baseUrl$path")
                    .post(body)
                    .build()
            ).execute()
            parseResponse(response, expectEnvelope)
        } catch (e: IOException) {
            Timber.tag(TAG).e(e, "POST(multipart) $path network error")
            ApiResult.NetworkError(e.message ?: Strings.error_network_connection_failed.str())
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "POST(multipart) $path unknown error")
            ApiResult.Error(-1, e.message ?: Strings.error_unknown.str())
        }
    }

    private fun guessLogMediaType(fileName: String) = when {
        fileName.endsWith(".zip", ignoreCase = true) -> "application/zip".toMediaType()
        fileName.endsWith(".log", ignoreCase = true) -> "text/plain".toMediaType()
        fileName.endsWith(".txt", ignoreCase = true) -> "text/plain".toMediaType()
        else -> "application/octet-stream".toMediaType()
    }

    private inline fun <reified T> parseResponse(
        response: okhttp3.Response,
        expectEnvelope: Boolean
    ): ApiResult<T> {
        val responseBody = response.body?.string()

        if (responseBody.isNullOrBlank()) {
            return if (response.isSuccessful) {
                ApiResult.Error(-1, Strings.error_response_empty.str())
            } else {
                ApiResult.Error(response.code, Strings.error_request_failed_with_code.str(response.code))
            }
        }

        if (!expectEnvelope) {
            if (!response.isSuccessful) {
                val errorMessage = parseErrorMessage(responseBody)
                return ApiResult.Error(
                    code = response.code,
                    message = errorMessage ?: Strings.error_request_failed_with_code.str(response.code)
                )
            }

            return runCatching {
                ApiResult.Success(json.decodeFromString<T>(responseBody))
            }.getOrElse {
                Timber.tag(TAG).e(it, "Failed to parse raw response: $responseBody")
                ApiResult.Error(-1, Strings.error_response_parse_failed.str())
            }
        }

        return when (val parsed = ApiEnvelopeParser.parse<T>(responseBody)) {
            is ApiEnvelopeParseResult.Success -> ApiResult.Success(parsed.data)
            is ApiEnvelopeParseResult.ApiError -> ApiResult.Error(
                code = response.code,
                message = ApiEnvelopeParser.formatApiError(parsed.apiCode, parsed.message)
            )
            is ApiEnvelopeParseResult.InvalidFormat -> {
                Timber.tag(TAG).e(
                    "Invalid API envelope (http=${response.code}, ok=${response.isSuccessful}): ${parsed.reason}"
                )
                val errorMessage = if (!response.isSuccessful) parseErrorMessage(responseBody) else null
                ApiResult.Error(
                    code = if (response.isSuccessful) -1 else response.code,
                    message = errorMessage ?: Strings.error_response_parse_failed.str()
                )
            }
        }
    }

    private fun parseErrorMessage(body: String?): String? {
        if (body.isNullOrBlank()) return null

        ApiEnvelopeParser.parseError(body)?.let { err ->
            return ApiEnvelopeParser.formatApiError(err.apiCode, err.message)
        }

        return runCatching {
            val jsonElement = json.parseToJsonElement(body)
            val obj = runCatching { jsonElement.jsonObject }.getOrNull()
                ?: return@runCatching body.trim().takeIf { it.isNotEmpty() }
            obj["message"]?.jsonPrimitive?.content
                ?: body.trim().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
}

@Serializable
data class HealthResponse(
    val status: String,
    val version: String? = null
)

@Serializable
data class LogUploadResponse(
    val id: String,
    val message: String
)

@Serializable
data class ServerConfigPayload(
    @SerialName("protocol_version")
    val protocolVersion: Int,
    val timestamp: Long,
    val data: ByteArray,
    val signature: ByteArray
)

@Serializable
data class ServerConfigResponse(
    @SerialName("version")
    val version: Long = 0,
    @SerialName("updated_at")
    val updatedAt: String? = null,
    @SerialName("config_refresh_interval_secs")
    val configRefreshIntervalSecs: Long = 300,
    @SerialName("features")
    val features: FeatureFlags = FeatureFlags(),
    @SerialName("client")
    val client: ClientConfig = ClientConfig()
)

@Serializable
data class FeatureFlags(
    @SerialName("plugin_market_enabled")
    val pluginMarketEnabled: Boolean = true,
    @SerialName("package_manager_enabled")
    val packageManagerEnabled: Boolean = true,
    @SerialName("developer_options_enabled")
    val developerOptionsEnabled: Boolean = true
)

@Serializable
data class ClientConfig(
    @SerialName("min_client_version")
    val minClientVersion: String = "1.0.0",
    @SerialName("recommended_client_version")
    val recommendedClientVersion: String = "1.0.0",
    @SerialName("force_update")
    val forceUpdate: Boolean = false
)
