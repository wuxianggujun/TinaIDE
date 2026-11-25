package com.wuxianggujun.tinaide.core.network

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.Response
import okhttp3.ResponseBody
import okio.ForwardingSource
import okio.buffer

/**
 * 以「协程可取消」的方式执行一个 OkHttp [Call] 并返回其 [Response]。
 *
 * 直接调用 [Call.execute] 是阻塞的：协程被取消时只会置标志位，线程仍会卡在
 * connect/read 上直到超时（download 客户端为 30~60s），体感就是「点了半天没反应」。
 * 本扩展把 [Call] 的生命周期绑定到当前协程——协程一旦被取消，立即关闭底层 socket，
 * 使阻塞的 execute()/read() 立刻抛出 IOException 退出，从而实现
 * 「点击取消即断开网络连接，重新点击即重新建连」。
 *
 * 回调在 Job 开始取消时执行，覆盖「建连 + 拿响应头 + 读取响应体」整个过程。
 * 不能等待 Job 完成，否则阻塞读取和取消回调会相互等待。
 *
 * 调用方拿到 [Response] 后请自行 `use { }` 或在读取循环里 `ensureActive()`，使取消以
 * `CancellationException` 形式干净退出。响应体关闭时解除绑定，避免长任务积累回调。
 */
@OptIn(InternalCoroutinesApi::class)
suspend fun Call.executeCancellable(): Response {
    // The default completion handler runs only AFTER a blocked read returns. Listen
    // to cancellation starting, and retain that registration until the body closes.
    val cancellation = coroutineContext[Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
        if (cause != null) cancel()
    }
    try {
        coroutineContext.ensureActive()
        val response = execute()
        try {
            coroutineContext.ensureActive()
        } catch (error: Throwable) {
            response.close()
            throw error
        }
        val body = response.body
        if (body == null) {
            cancellation?.dispose()
            return response
        }
        val source = object : ForwardingSource(body.source()) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    cancellation?.dispose()
                }
            }
        }.buffer()
        return response.newBuilder().body(object : ResponseBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun source() = source
        }).build()
    } catch (error: Throwable) {
        cancellation?.dispose()
        coroutineContext.ensureActive()
        throw error
    }
}
