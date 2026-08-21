package com.wuxianggujun.tinaide.core.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import okhttp3.ResponseBody

class ResponseBodyTooLargeException(
    val maxBytes: Int,
) : IOException("Response body exceeds the $maxBytes byte limit")

fun ResponseBody.readBytesLimited(maxBytes: Int): ByteArray {
    require(maxBytes > 0) { "Response body limit must be positive" }
    if (contentLength() > maxBytes) {
        throw ResponseBodyTooLargeException(maxBytes)
    }

    val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    byteStream().use { input ->
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > maxBytes - total) {
                throw ResponseBodyTooLargeException(maxBytes)
            }
            output.write(buffer, 0, count)
            total += count
        }
    }
    return output.toByteArray()
}

fun ResponseBody.readUtf8Limited(maxBytes: Int): String =
    String(readBytesLimited(maxBytes), StandardCharsets.UTF_8).removePrefix("\uFEFF")

private const val DEFAULT_BUFFER_SIZE = 8 * 1024
