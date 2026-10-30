package com.wuxianggujun.tinaide.core.git.ssh

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GitAtomicFileIoTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun repeatedWrites_replaceWholeFileWithUtf8Content() {
        val target = File(temporaryFolder.root, "ssh/state.json")

        writeUtf8Atomically(target, "original content that is longer")
        writeUtf8Atomically(target, "密钥状态 😀\n")

        assertThat(target.readBytes()).isEqualTo("密钥状态 😀\n".toByteArray(Charsets.UTF_8))
        assertThat(target.parentFile!!.list()).asList().containsExactly("state.json")
    }

    @Test
    fun failedPublication_preservesCommittedContentAndCleansStagingFile() {
        val target = temporaryFolder.newFile("state.json")
        target.writeText("committed", Charsets.UTF_8)
        val failure = IOException("Publication failed")

        val actual = assertThrows(IOException::class.java) {
            writeUtf8Atomically(target, "replacement") { staged, destination ->
                assertThat(staged.parentFile).isEqualTo(destination.parentFile)
                assertThat(staged.readText(Charsets.UTF_8)).isEqualTo("replacement")
                throw failure
            }
        }

        assertThat(actual).isSameInstanceAs(failure)
        assertThat(target.exists()).isTrue()
        assertThat(target.readText(Charsets.UTF_8)).isEqualTo("committed")
        assertThat(temporaryFolder.root.list()).asList().containsExactly("state.json")
    }

    @Test
    fun unsupportedAtomicMove_doesNotFallBackToDestructiveReplacement() {
        val target = temporaryFolder.newFile("state.json")
        target.writeText("committed", Charsets.UTF_8)

        assertThrows(AtomicMoveNotSupportedException::class.java) {
            writeUtf8Atomically(target, "replacement") { staged, destination ->
                throw AtomicMoveNotSupportedException(staged.name, destination.name, "test")
            }
        }

        assertThat(target.exists()).isTrue()
        assertThat(target.readText(Charsets.UTF_8)).isEqualTo("committed")
        assertThat(temporaryFolder.root.list()).asList().containsExactly("state.json")
    }

    @Test
    fun publication_doesNotReplaceAnExistingDirectory() {
        val target = temporaryFolder.newFolder("state.json")

        assertThrows(IOException::class.java) {
            writeUtf8Atomically(target, "replacement")
        }

        assertThat(target.isDirectory).isTrue()
        assertThat(temporaryFolder.root.list()).asList().containsExactly("state.json")
    }

    @Test
    fun staging_doesNotOverwriteAnotherKeyNamedWithNewSuffix() {
        val target = temporaryFolder.newFile("id_ed25519")
        val otherKey = temporaryFolder.newFile("id_ed25519.new")
        otherKey.writeText("another key", Charsets.UTF_8)

        writeUtf8Atomically(target, "replacement")

        assertThat(target.readText(Charsets.UTF_8)).isEqualTo("replacement")
        assertThat(otherKey.exists()).isTrue()
        assertThat(otherKey.readText(Charsets.UTF_8)).isEqualTo("another key")
    }

    @Test
    fun failedFirstPublication_leavesNoPartialFile() {
        val target = File(temporaryFolder.root, "state.json")

        assertThrows(IOException::class.java) {
            writeUtf8Atomically(target, "replacement") { _, _ ->
                throw IOException("Publication failed")
            }
        }

        assertThat(target.exists()).isFalse()
        assertThat(temporaryFolder.root.list()).asList().isEmpty()
    }

    @Test
    fun cleanupFailure_isSuppressedWithoutHidingPublicationFailure() {
        val target = temporaryFolder.newFile("state.json")
        target.writeText("committed", Charsets.UTF_8)
        val failure = IOException("Publication failed")

        val actual = assertThrows(IOException::class.java) {
            writeUtf8Atomically(target, "replacement") { staged, _ ->
                check(staged.delete())
                check(staged.mkdir())
                File(staged, "child").writeText("blocks cleanup", Charsets.UTF_8)
                throw failure
            }
        }

        assertThat(actual).isSameInstanceAs(failure)
        assertThat(actual.suppressed).asList().hasSize(1)
        assertThat(actual.suppressed.single()).isInstanceOf(IOException::class.java)
        assertThat(target.readText(Charsets.UTF_8)).isEqualTo("committed")
    }
}
