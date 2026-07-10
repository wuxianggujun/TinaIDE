package com.wuxianggujun.tinaide.editor.io

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createTempDirectory
import org.junit.Test

class AtomicTextFileWriterTest {
    @Test
    fun write_shouldReplaceContentWithoutLeavingTemporaryFile() {
        val directory = createTempDirectory("atomic-text-writer-")
        try {
            val target = directory.resolve("source.cpp").toFile().apply { writeText("old") }

            AtomicTextFileWriter.write(target, "新内容", Charsets.UTF_8)

            assertThat(target.readText(Charsets.UTF_8)).isEqualTo("新内容")
            assertThat(directory.toFile().listFiles().orEmpty().map { it.name })
                .containsExactly("source.cpp")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun write_shouldPreserveSymbolicLinkWhenSupported() {
        val directory = createTempDirectory("atomic-text-writer-link-")
        try {
            val realFile = directory.resolve("real.txt").apply { Files.writeString(this, "old") }
            val link = directory.resolve("link.txt")
            if (runCatching { Files.createSymbolicLink(link, realFile.fileName) }.isFailure) return

            AtomicTextFileWriter.write(link.toFile(), "new", Charsets.UTF_8)

            assertThat(Files.isSymbolicLink(link)).isTrue()
            assertThat(Files.readString(realFile)).isEqualTo("new")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun write_shouldPreservePosixPermissionsWhenSupported() {
        val directory = createTempDirectory("atomic-text-writer-mode-")
        try {
            val target = directory.resolve("run.sh")
            Files.writeString(target, "old")
            val fileStore = Files.getFileStore(target)
            if (!fileStore.supportsFileAttributeView("posix")) return
            val permissions = setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ
            )
            Files.setPosixFilePermissions(target, permissions)

            AtomicTextFileWriter.write(target.toFile(), "new", Charsets.UTF_8)

            assertThat(Files.getPosixFilePermissions(target)).containsExactlyElementsIn(permissions)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
