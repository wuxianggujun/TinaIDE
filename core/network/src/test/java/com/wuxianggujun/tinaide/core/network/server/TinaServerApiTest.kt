package com.wuxianggujun.tinaide.core.network.server

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.network.ApiResult
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Test

class TinaServerApiTest {

    @After
    fun tearDown() {
        TinaServerApi.resetInstance()
    }

    @Test
    fun healthCheck_truncatesUntrustedServerErrorMessage() = runTest {
        val oversizedMessage = "x".repeat(8 * 1024)
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(500)
                        .message("Server Error")
                        .body(
                            """{"message":"$oversizedMessage"}"""
                                .toResponseBody("application/json".toMediaType())
                        )
                        .build()
                }
            )
            .build()
        val api = TinaServerApi.getInstance("https://server.test", client)

        val result = api.healthCheck()

        assertThat(result).isInstanceOf(ApiResult.Error::class.java)
        assertThat((result as ApiResult.Error).message).hasLength(4 * 1024)
    }

    @Test
    fun serverConfigTimestampValidation_rejectsOverflowExtremes() {
        val nowSecs = 2_000_000_000L

        assertThat(TinaServerApi.isServerConfigTimestampAllowed(nowSecs, nowSecs)).isTrue()
        assertThat(TinaServerApi.isServerConfigTimestampAllowed(Long.MIN_VALUE, nowSecs)).isFalse()
        assertThat(TinaServerApi.isServerConfigTimestampAllowed(Long.MAX_VALUE, nowSecs)).isFalse()
    }
}
