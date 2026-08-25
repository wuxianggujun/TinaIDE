package com.wuxianggujun.tinaide.core.network.api

import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str
import com.wuxianggujun.tinaide.core.network.ApiEnvelopeParser
import com.wuxianggujun.tinaide.core.network.ApiResult
import com.wuxianggujun.tinaide.core.network.OkHttpClientProvider
import com.wuxianggujun.tinaide.core.network.readUtf8Limited
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

/**
 * 用户内容 API 客户端
 *
 * 负责收藏、下载历史等用户内容的网络请求
 */
class UserContentApiClient private constructor(
    private val baseUrl: String,
    private val client: OkHttpClient
) {

    companion object {
        private const val TAG = "UserContentApiClient"
        private const val MAX_API_RESPONSE_BYTES = 8 * 1024 * 1024

        @Volatile
        private var instance: UserContentApiClient? = null

        fun getInstance(baseUrl: String): UserContentApiClient = instance ?: synchronized(this) {
            instance ?: createInstance(baseUrl).also { instance = it }
        }

        private fun createInstance(baseUrl: String): UserContentApiClient = UserContentApiClient(
            baseUrl = baseUrl.trimEnd('/'),
            client = OkHttpClientProvider.default
        )

        fun resetInstance() {
            instance = null
        }
    }

    /**
     * 添加收藏
     */
    suspend fun addFavorite(pluginId: String): ApiResult<Unit> = postUnit("/api/user/favorites/$pluginId")

    /**
     * 取消收藏
     */
    suspend fun removeFavorite(pluginId: String): ApiResult<Unit> = deleteUnit("/api/user/favorites/$pluginId")

    /**
     * 获取收藏列表
     */
    suspend fun getFavorites(page: Int = 1, pageSize: Int = 20): ApiResult<FavoritesListResponse> = get("/api/user/favorites?page=$page&page_size=$pageSize")

    private suspend inline fun <reified T> get(path: String): ApiResult<T> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl$path")
                .get()
                .build()

            val response = client.newCall(request).execute()
            parseResponse(response)
        } catch (e: IOException) {
            Timber.tag(TAG).e(e, "GET $path network error")
            ApiResult.NetworkError(e.message ?: Strings.error_network_connection_failed.str())
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "GET $path unknown error")
            ApiResult.Error(-1, e.message ?: Strings.error_unknown.str())
        }
    }

    private suspend fun postUnit(path: String): ApiResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl$path")
                .post("".toRequestBody())
                .build()

            val response = client.newCall(request).execute()
            parseUnitResponse(response)
        } catch (e: IOException) {
            Timber.tag(TAG).e(e, "POST $path network error")
            ApiResult.NetworkError(e.message ?: Strings.error_network_connection_failed.str())
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "POST $path unknown error")
            ApiResult.Error(-1, e.message ?: Strings.error_unknown.str())
        }
    }

    private suspend fun deleteUnit(path: String): ApiResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl$path")
                .delete()
                .build()

            val response = client.newCall(request).execute()
            parseUnitResponse(response)
        } catch (e: IOException) {
            Timber.tag(TAG).e(e, "DELETE $path network error")
            ApiResult.NetworkError(e.message ?: Strings.error_network_connection_failed.str())
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "DELETE $path unknown error")
            ApiResult.Error(-1, e.message ?: Strings.error_unknown.str())
        }
    }

    private inline fun <reified T> parseResponse(response: okhttp3.Response): ApiResult<T> = response.use { closedResponse ->
        val responseBody = closedResponse.body?.readUtf8Limited(MAX_API_RESPONSE_BYTES)
        if (responseBody.isNullOrEmpty()) {
            return ApiResult.Error(closedResponse.code, Strings.error_response_empty.str())
        }
        return ApiEnvelopeParser.parseToApiResult<T>(responseBody, closedResponse.code, TAG)
    }

    private fun parseUnitResponse(response: okhttp3.Response): ApiResult<Unit> = response.use { closedResponse ->
        val responseBody = closedResponse.body?.readUtf8Limited(MAX_API_RESPONSE_BYTES)
        if (responseBody.isNullOrEmpty()) {
            // Unit 类型：空响应视为成功
            return ApiResult.Success(Unit)
        }
        return ApiEnvelopeParser.checkOk(responseBody, closedResponse.code)
    }
}

@Serializable
data class FavoritesListResponse(
    val favorites: List<FavoriteItemResponse>,
    val total: Int,
    val page: Int,
    val pageSize: Int
)

@Serializable
data class FavoriteItemResponse(
    val favoriteId: String,
    val pluginId: String,
    val name: String,
    val description: String? = null,
    val iconUrl: String? = null,
    val category: String? = null,
    val tags: List<String>? = null,
    val latestVersion: String? = null,
    val addedAt: String
)
