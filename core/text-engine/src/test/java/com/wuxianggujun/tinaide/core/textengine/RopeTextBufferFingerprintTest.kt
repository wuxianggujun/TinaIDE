package com.wuxianggujun.tinaide.core.textengine

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RopeTextBufferFingerprintTest {

    /**
     * DocumentSession 在不支持分片指纹的 binding 上仍会走"物化文本 + 逐字符 FNV-1a"。
     * 两条路径必须给出同一个哈希，否则脏标记会误判。
     */
    private fun referenceFingerprint(text: String): Pair<Int, Long> {
        var hash = -0x340d631b8c4675d9L
        val prime = 0x100000001b3L
        for (ch in text) {
            hash = hash xor ch.code.toLong()
            hash *= prime
        }
        return text.length to hash
    }

    private fun assertMatchesReference(text: String) {
        val buffer = RopeTextBuffer(text)
        val (expectedLength, expectedHash) = referenceFingerprint(text)
        val actual = buffer.contentFingerprint()

        assertThat(actual.length).isEqualTo(expectedLength)
        assertThat(actual.hash).isEqualTo(expectedHash)
    }

    @Test
    fun contentFingerprint_shouldMatchStringLoopForEmptyText() {
        assertMatchesReference("")
    }

    @Test
    fun contentFingerprint_shouldMatchStringLoopForShortText() {
        assertMatchesReference("hello\nworld")
    }

    @Test
    fun contentFingerprint_shouldMatchStringLoopAcrossChunkBoundaries() {
        // Rope 分片为 4096 char；跨多个叶子才能验证分片折叠顺序正确。
        val text = (0 until 5000).joinToString("\n") { "line $it with some padding text" }
        assertMatchesReference(text)
    }

    @Test
    fun contentFingerprint_shouldMatchStringLoopForSurrogatePairs() {
        assertMatchesReference("a😀b😁")
    }

    @Test
    fun contentFingerprint_shouldTrackEdits() {
        val buffer = RopeTextBuffer("abc")
        val before = buffer.contentFingerprint()

        buffer.insert(3, "d")
        val after = buffer.contentFingerprint()

        assertThat(after).isNotEqualTo(before)
        val (expectedLength, expectedHash) = referenceFingerprint("abcd")
        assertThat(after.length).isEqualTo(expectedLength)
        assertThat(after.hash).isEqualTo(expectedHash)
    }

    @Test
    fun contentFingerprint_shouldMatchAfterEditsAcrossChunks() {
        val buffer = RopeTextBuffer((0 until 3000).joinToString("\n") { "row $it" })
        buffer.insert(buffer.length / 2, "INSERTED")
        buffer.delete(0, 4)

        val text = buffer.toString()
        val (expectedLength, expectedHash) = referenceFingerprint(text)
        val actual = buffer.contentFingerprint()

        assertThat(actual.length).isEqualTo(expectedLength)
        assertThat(actual.hash).isEqualTo(expectedHash)
    }

    @Test
    fun fingerprintSnapshot_shouldReportMatchingVersion() {
        val buffer = RopeTextBuffer("abc")
        buffer.insert(3, "d")

        val snapshot = buffer.fingerprintSnapshot()

        assertThat(snapshot.documentVersion).isEqualTo(buffer.version)
        assertThat(snapshot.fingerprint).isEqualTo(buffer.contentFingerprint())
    }

    @Test
    fun replaceAllOffThread_shouldMatchReplaceAllBehaviour() = runTest {
        val eager = RopeTextBuffer("old text")
        val offThread = RopeTextBuffer("old text")
        val newText = (0 until 2000).joinToString("\n") { "line $it" }

        val eagerChanges = mutableListOf<TextChange>()
        val offThreadChanges = mutableListOf<TextChange>()
        eager.addChangeListener(eagerChanges::add)
        offThread.addChangeListener(offThreadChanges::add)

        eager.replaceAll(newText)
        offThread.replaceAllOffThread(newText)

        assertThat(offThread.toString()).isEqualTo(eager.toString())
        assertThat(offThread.lineCount).isEqualTo(eager.lineCount)
        assertThat(offThread.version).isEqualTo(eager.version)
        assertThat(offThread.contentFingerprint()).isEqualTo(eager.contentFingerprint())
        assertThat(offThreadChanges).hasSize(eagerChanges.size)
        assertThat(offThreadChanges.single().newText).isEqualTo(eagerChanges.single().newText)
    }

    @Test
    fun replaceAllOffThread_sameTextShouldNotAdvanceVersion() = runTest {
        val buffer = RopeTextBuffer("same")
        var changeCount = 0
        buffer.addChangeListener { changeCount++ }

        buffer.replaceAllOffThread("same")

        assertThat(buffer.version).isEqualTo(0L)
        assertThat(changeCount).isEqualTo(0)
    }

    @Test
    fun loadFromFile_shouldProduceFingerprintMatchingReference() = runTest {
        val tempDir = createTempDirectory(prefix = "tina-fingerprint-").toFile()
        try {
            val text = (0 until 4000).joinToString("\n") { "content line $it" }
            val source = File(tempDir, "in.txt").apply { writeText(text) }
            val buffer = RopeTextBuffer()

            val result = buffer.loadFromFile(source)

            assertThat(result.isSuccess).isTrue()
            val (expectedLength, expectedHash) = referenceFingerprint(text)
            assertThat(buffer.contentFingerprint().length).isEqualTo(expectedLength)
            assertThat(buffer.contentFingerprint().hash).isEqualTo(expectedHash)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
