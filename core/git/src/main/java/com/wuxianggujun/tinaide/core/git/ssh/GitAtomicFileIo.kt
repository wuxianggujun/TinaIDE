package com.wuxianggujun.tinaide.core.git.ssh

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 确定性原子写：写 `<file>.new` 临时文件，fsync 后显式替换目标文件。
 *
 * 不用 [android.util.AtomicFile]：其 `finishWrite` 在 Robolectric 的实现里
 * `renameTo` 失败会被静默吞掉，重复写入同一文件时（Windows 测试环境）旧内容
 * 原样保留、数据无声丢失。这里任何一步失败都抛 [IOException]，不吞错。
 */
internal fun writeUtf8Atomically(file: File, content: String) {
    val tempFile = File(file.parentFile, file.name + ".new")
    file.parentFile?.mkdirs()
    try {
        FileOutputStream(tempFile).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.flush()
            runCatching { output.fd.sync() }
        }
        if (file.exists() && !file.delete()) {
            throw IOException("Failed to replace previous file: ${file.name}")
        }
        if (!tempFile.renameTo(file)) {
            throw IOException("Failed to publish file: ${file.name}")
        }
    } catch (error: Throwable) {
        tempFile.delete()
        throw error
    }
}
