package com.wuxianggujun.tinaide.editor.io

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createTempDirectory
import org.junit.Test

class AtomicTextFileWriterTest {
    @Test
    fun write_shouldPreserveOriginalAndRemoveTemporaryFileWhenEncodingWouldLoseCharacters() {
        val directory = createTempDirectory("atomic-text-writer-encoding-")
        try {
            val target = directory.resolve("source.cpp").toFile()
            val gbk = Charset.forName("GBK")
            target.writeText("旧内容", gbk)
            val original = target.readBytes()

            val result = runCatching { AtomicTextFileWriter.write(target, "中文🙂", gbk) }

            assertThat(result.exceptionOrNull()).isInstanceOf(CharacterCodingException::class.java)
            assertThat(target.readBytes()).isEqualTo(original)
            assertThat(directory.toFile().listFiles().orEmpty().map { it.name }).containsExactly("source.cpp")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun write_shouldRejectUnpairedSurrogateWithoutReplacingOriginal() {
        val directory = createTempDirectory("atomic-text-writer-surrogate-")
        try {
            val target = directory.resolve("source.cpp").toFile().apply { writeText("original") }
            val result = runCatching { AtomicTextFileWriter.write(target, "\uD83D", Charsets.UTF_8) }
            assertThat(result.exceptionOrNull()).isInstanceOf(CharacterCodingException::class.java)
            assertThat(target.readText()).isEqualTo("original")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

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
            val realFile = directory.resolve("real.txt").apply {
                Files.write(this, "old".toByteArray(Charsets.UTF_8))
            }
            val link = directory.resolve("link.txt")
            if (runCatching { Files.createSymbolicLink(link, realFile.fileName) }.isFailure) return

            AtomicTextFileWriter.write(link.toFile(), "new", Charsets.UTF_8)

            assertThat(Files.isSymbolicLink(link)).isTrue()
            assertThat(Files.readAllBytes(realFile).toString(Charsets.UTF_8)).isEqualTo("new")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun write_shouldPreservePosixPermissionsWhenSupported() {
        val directory = createTempDirectory("atomic-text-writer-mode-")
        try {
            val target = directory.resolve("run.sh")
            Files.write(target, "old".toByteArray(Charsets.UTF_8))
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
