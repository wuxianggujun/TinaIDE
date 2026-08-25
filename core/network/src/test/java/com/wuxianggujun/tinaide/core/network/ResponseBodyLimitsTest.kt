package com.wuxianggujun.tinaide.core.network

import com.google.common.truth.Truth.assertThat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Test

class ResponseBodyLimitsTest {

    @Test
    fun readUtf8Limited_returnsBodyAtLimit() {
        val body = "hello".toResponseBody("application/json".toMediaType())

        assertThat(body.readUtf8Limited(5)).isEqualTo("hello")
    }

    @Test
    fun readUtf8Limited_rejectsDeclaredOversizedBody() {
        val body = "oversized".toResponseBody("application/json".toMediaType())

        val error = runCatching { body.readUtf8Limited(4) }.exceptionOrNull()

        assertThat(error).isInstanceOf(ResponseBodyTooLargeException::class.java)
    }

    @Test
    fun readUtf8Limited_rejectsChunkedOversizedBody() {
        val payload = "oversized".toByteArray()
        val body = object : okhttp3.ResponseBody() {
            override fun contentType() = "application/json".toMediaType()

            override fun contentLength(): Long = -1L

            override fun source(): BufferedSource = Buffer().write(payload)
        }

        val error = runCatching { body.readUtf8Limited(4) }.exceptionOrNull()

        assertThat(error).isInstanceOf(ResponseBodyTooLargeException::class.java)
    }

    @Test
    fun readBytesLimited_returnsBinaryBodyAtLimit() {
        val payload = byteArrayOf(0, 1, 2, 3)
        val body = payload.toResponseBody("application/msgpack".toMediaType())

        assertThat(body.readBytesLimited(payload.size).asList())
            .containsExactlyElementsIn(payload.asList())
            .inOrder()
    }
}
