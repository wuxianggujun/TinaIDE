package com.wuxianggujun.tinaide.core.git.ssh

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 仅用于 app-private SSH 密钥与元数据，不修改用户项目文件。
 *
 * 同目录唯一临时文件完成 UTF-8 写入和 fsync 后再原子替换；不预先删除旧文件，
 * 也不降级为非原子覆盖。发布失败保留旧内容，清理失败作为 suppressed 异常上抛。
 */
internal fun writeUtf8Atomically(
    file: File,
    content: String,
    publish: (File, File) -> Unit = ::publishAtomicFile,
) {
    val target = file.absoluteFile
    val parent = target.parentFile ?: throw IOException("Missing parent directory: ${file.name}")
    Files.createDirectories(parent.toPath())
    if (target.isDirectory) {
        throw IOException("Target is a directory: ${target.name}")
    }
    // 固定的 <key>.new 可能本身就是另一个合法密钥名，不能占用或覆盖它。
    val tempFile = Files.createTempFile(parent.toPath(), ".${target.name}.", ".new").toFile()
    try {
        FileOutputStream(tempFile).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        publish(tempFile, target)
    } catch (error: Throwable) {
        try {
            if (tempFile.exists() && !tempFile.delete()) {
                error.addSuppressed(IOException("Failed to clean staged file: ${tempFile.name}"))
            }
        } catch (cleanupError: Throwable) {
            error.addSuppressed(cleanupError)
        }
        throw error
    }
}

private fun publishAtomicFile(source: File, target: File) {
    Files.move(
        source.toPath(),
        target.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
    )
}
