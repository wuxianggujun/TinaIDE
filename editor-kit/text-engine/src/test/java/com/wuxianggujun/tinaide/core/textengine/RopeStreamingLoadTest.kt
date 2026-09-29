package com.wuxianggujun.tinaide.core.textengine

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * 流式加载必须与「读全文 + replaceAll」在可观察状态上完全等价：
 * 正文、行数、行边界、指纹都要一致。只有 TextChange 的 newText 携带方式不同。
 */
class RopeStreamingLoadTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = createTempDirectory(prefix = "tina-streaming-").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun assertStreamingMatchesEager(text: String, label: String) = runTest {
        val source = File(tempDir, "in.txt").apply { writeText(text) }

        val streamed = RopeTextBuffer()
        val loadResult = streamed.loadFromFile(source)
        assertThat(loadResult.isSuccess).isTrue()

        val eager = RopeTextBuffer()
        eager.replaceAll(text)

        assertThat(streamed.toString()).isEqualTo(eager.toString())
        assertThat(streamed.length).isEqualTo(eager.length)
        assertThat(streamed.lineCount).isEqualTo(eager.lineCount)
        assertThat(streamed.contentFingerprint()).isEqualTo(eager.contentFingerprint())

        // 逐行比对行边界，确保 appendChunk 的累计偏移没有错位
        for (line in 0 until eager.lineCount) {
            assertThat(streamed.getLineStart(line)).isEqualTo(eager.getLineStart(line))
            assertThat(streamed.getLineEnd(line)).isEqualTo(eager.getLineEnd(line))
            assertThat(streamed.getLine(line)).isEqualTo(eager.getLine(line))
        }
    }

    @Test
    fun streamingLoad_shouldMatchEagerForEmptyFile() {
        assertStreamingMatchesEager("", "empty")
    }

    @Test
    fun streamingLoad_shouldMatchEagerForSingleLine() {
        assertStreamingMatchesEager("hello world", "single line")
    }

    @Test
    fun streamingLoad_shouldMatchEagerForTrailingNewline() {
        assertStreamingMatchesEager("a\nb\nc\n", "trailing newline")
    }

    @Test
    fun streamingLoad_shouldMatchEagerForCrlf() {
        assertStreamingMatchesEager("a\r\nb\r\nc\r\n", "crlf")
    }

    @Test
    fun streamingLoad_shouldMatchEagerForConsecutiveNewlines() {
        assertStreamingMatchesEager("a\n\n\n\nb", "consecutive newlines")
    }

    /**
     * IO 缓冲为 16K char、rope 叶子为 4096 char。这个尺寸会跨越多个 IO 读和多个叶子边界，
     * 是 appendChunk 累计偏移最容易出错的地方。
     */
    @Test
    fun streamingLoad_shouldMatchEagerAcrossChunkBoundaries() {
        val text = (0 until 6000).joinToString("\n") { "line $it with padding to push past chunk edges" }
        assertStreamingMatchesEager(text, "multi chunk")
    }

    @Test
    fun streamingLoad_shouldMatchEagerWhenNewlineLandsOnChunkBoundary() {
        // 构造一个正好让 '\n' 落在 4096 边界上的文本
        val prefix = "x".repeat(4095)
        assertStreamingMatchesEager("$prefix\nrest of document", "newline on boundary")
    }

    @Test
    fun streamingLoad_shouldMatchEagerForSurrogatePairs() {
        val text = (0 until 3000).joinToString("\n") { "row $it 😀😁" }
        assertStreamingMatchesEager(text, "surrogate pairs")
    }

    @Test
    fun streamingLoad_shouldEmitIncompleteNewTextChange() = runTest {
        val text = (0 until 500).joinToString("\n") { "line $it" }
        val source = File(tempDir, "change.txt").apply { writeText(text) }
        val buffer = RopeTextBuffer("previous content")
        val changes = mutableListOf<TextChange>()
        buffer.addChangeListener(changes::add)

        val result = buffer.loadFromFile(source)

        assertThat(result.isSuccess).isTrue()
        assertThat(changes).hasSize(1)
        val change = changes.single()
        // newText 不携带正文，但长度与行数元数据必须准确
        assertThat(change.hasCompleteNewText).isFalse()
        assertThat(change.newText).isEmpty()
        assertThat(change.newTextLength).isEqualTo(text.length)
        assertThat(change.newLineBreakCount).isEqualTo(text.count { it == '\n' })
        assertThat(change.hasCompleteOldText).isFalse()
        assertThat(change.oldTextLength).isEqualTo("previous content".length)
    }

    @Test
    fun streamingLoad_shouldReplacePreviousContentEntirely() = runTest {
        val buffer = RopeTextBuffer("old content that is longer than the new one")
        val source = File(tempDir, "short.txt").apply { writeText("new") }

        assertThat(buffer.loadFromFile(source).isSuccess).isTrue()

        assertThat(buffer.toString()).isEqualTo("new")
        assertThat(buffer.length).isEqualTo(3)
        assertThat(buffer.lineCount).isEqualTo(1)
    }

    @Test
    fun streamingLoad_shouldClearUndoHistory() = runTest {
        val buffer = RopeTextBuffer("abc")
        buffer.insert(3, "def")
        assertThat(buffer.canUndo()).isTrue()

        val source = File(tempDir, "fresh.txt").apply { writeText("fresh") }
        assertThat(buffer.loadFromFile(source).isSuccess).isTrue()

        assertThat(buffer.canUndo()).isFalse()
        assertThat(buffer.toString()).isEqualTo("fresh")
    }

    @Test
    fun streamingLoad_shouldSupportEditingAfterLoad() = runTest {
        val text = (0 until 2000).joinToString("\n") { "row $it" }
        val source = File(tempDir, "edit.txt").apply { writeText(text) }
        val buffer = RopeTextBuffer()
        assertThat(buffer.loadFromFile(source).isSuccess).isTrue()

        val insertAt = buffer.getLineStart(1000)
        buffer.insert(insertAt, "INSERTED ")

        val expected = StringBuilder(text).insert(insertAt, "INSERTED ").toString()
        assertThat(buffer.toString()).isEqualTo(expected)
        assertThat(buffer.lineCount).isEqualTo(expected.count { it == '\n' } + 1)
        // 编辑后行索引仍需自洽
        assertThat(buffer.getLine(1000)).isEqualTo(expected.lines()[1000])
    }

    @Test
    fun streamingLoad_shouldRoundTripThroughSave() = runTest {
        val text = (0 until 1500).joinToString("\n") { "content $it" }
        val source = File(tempDir, "round-in.txt").apply { writeText(text) }
        val target = File(tempDir, "round-out.txt")
        val buffer = RopeTextBuffer()

        assertThat(buffer.loadFromFile(source).isSuccess).isTrue()
        assertThat(buffer.saveToFile(target).isSuccess).isTrue()

        assertThat(target.readText()).isEqualTo(text)
    }
}
