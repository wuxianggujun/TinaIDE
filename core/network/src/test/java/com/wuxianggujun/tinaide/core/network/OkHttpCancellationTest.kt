package com.wuxianggujun.tinaide.core.network

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Test

class OkHttpCancellationTest {
    @Test fun cancellationInterruptsExecuteBeforeTheCoroutineCanComplete() = runBlocking {
        val entered = CountDownLatch(1)
        val socketClosed = CountDownLatch(1)
        val call = mockk<Call>()
        every { call.execute() } answers {
            entered.countDown()
            check(socketClosed.await(5, TimeUnit.SECONDS)) { "Cancellation never closed the socket" }
            throw IOException("Canceled")
        }
        every { call.cancel() } answers { socketClosed.countDown() }
        val job = launch(Dispatchers.IO) { call.executeCancellable() }
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue()
            withTimeout(2_000) { job.cancelAndJoin() }
            assertThat(socketClosed.count).isEqualTo(0L)
        } finally {
            socketClosed.countDown()
            job.cancelAndJoin()
        }
    }

    @Test fun cancellationInterruptsResponseBodyRead() = runBlocking {
        val entered = CountDownLatch(1)
        val socketClosed = CountDownLatch(1)
        val body = object : ResponseBody() {
            private val input = object : Source {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    entered.countDown()
                    check(socketClosed.await(5, TimeUnit.SECONDS))
                    throw IOException("Canceled")
                }
                override fun close() = Unit
                override fun timeout() = Timeout.NONE
            }.buffer()
            override fun source() = input
            override fun contentLength() = -1L
            override fun contentType() = null
        }
        val call = mockk<Call>()
        every { call.execute() } returns response(body)
        every { call.cancel() } answers { socketClosed.countDown() }
        val job = launch(Dispatchers.IO) {
            try {
                call.executeCancellable().use { it.body!!.string() }
            } catch (_: IOException) {
                // Network clients translate this into cancellation at their boundary.
            }
        }
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue()
            withTimeout(2_000) { job.cancelAndJoin() }
            assertThat(socketClosed.count).isEqualTo(0L)
        } finally {
            socketClosed.countDown()
            job.cancelAndJoin()
        }
    }

    @Test fun closedResponseReleasesCancellationRegistration() = runBlocking {
        val call = mockk<Call>(relaxed = true)
        every { call.execute() } returns response("finished".toResponseBody())
        val closed = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.IO) {
            call.executeCancellable().use { assertThat(it.body!!.string()).isEqualTo("finished") }
            closed.complete(Unit)
            CompletableDeferred<Unit>().await()
        }
        withTimeout(2_000) { closed.await() }
        job.cancelAndJoin()
        verify(exactly = 0) { call.cancel() }
    }

    private fun response(body: ResponseBody): Response = Response.Builder()
        .request(Request.Builder().url("https://example.invalid/package").build())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(body)
        .build()
}
