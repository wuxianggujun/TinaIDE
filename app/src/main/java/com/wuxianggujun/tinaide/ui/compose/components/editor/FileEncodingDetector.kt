package com.wuxianggujun.tinaide.ui.compose.components.editor

import com.wuxianggujun.tinaide.editor.io.FileCharsetDetector
import java.io.File
import java.nio.charset.Charset

/**
 * 文件编码检测工具
 */
object FileEncodingDetector {

    // Read-only snippets can sample; editable documents use the full validation below.
    fun detectCharset(file: File): Charset = FileCharsetDetector.detect(file)

    suspend fun load(file: File, loader: suspend (Charset) -> Result<Unit>): Result<Charset> = try {
        val charset = kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
            FileCharsetDetector.detectForEditing(file)
        }
        loader(charset).getOrThrow()
        Result.success(charset)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
}
