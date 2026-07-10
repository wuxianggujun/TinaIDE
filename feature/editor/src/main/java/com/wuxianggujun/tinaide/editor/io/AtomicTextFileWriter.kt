package com.wuxianggujun.tinaide.editor.io

import java.io.File
import java.nio.charset.Charset
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission

object AtomicTextFileWriter {
    fun write(
        targetFile: File,
        content: String,
        charset: Charset
    ) {
        val requestedPath = targetFile.toPath()
        val targetPath = if (Files.isSymbolicLink(requestedPath)) {
            requestedPath.toRealPath()
        } else {
            requestedPath.toAbsolutePath().normalize()
        }
        val parent = requireNotNull(targetPath.parent) { "Target file has no parent directory" }
        Files.createDirectories(parent)

        val permissions = readPosixPermissions(targetPath)
        val tempPath = Files.createTempFile(parent, ".${targetPath.fileName}.", ".tmp")
        try {
            Files.newBufferedWriter(
                tempPath,
                charset,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
            ).use { writer ->
                writer.write(content)
            }
            permissions?.let { Files.setPosixFilePermissions(tempPath, it) }
            moveReplacing(tempPath, targetPath)
        } finally {
            runCatching { Files.deleteIfExists(tempPath) }
        }
    }

    private fun readPosixPermissions(targetPath: Path): Set<PosixFilePermission>? {
        if (!Files.exists(targetPath)) return null
        val fileStore = runCatching { Files.getFileStore(targetPath) }.getOrNull() ?: return null
        if (!fileStore.supportsFileAttributeView("posix")) return null
        return Files.getPosixFilePermissions(targetPath)
    }

    private fun moveReplacing(source: Path, target: Path) {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
