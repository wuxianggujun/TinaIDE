package com.wuxianggujun.tinaide.ui.runtime

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.editorview.EditorGutterMode
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], manifest = Config.NONE)
class GraphicalRuntimeLogDocumentTest {
    private val document = GraphicalRuntimeLogDocument()

    @After fun closeDocument() = document.close()

    @Test
    fun severityColorsCoverBodyAndMultilineMessagesAfterTrimming() {
        document.append((0..499).map { GraphicalRuntimeLogEntry('I', "row$it") })
        document.append(listOf(GraphicalRuntimeLogEntry('W', "warning\ntrace"), GraphicalRuntimeLogEntry('E', "failure")))
        assertThat(document.state.lineForegroundColors).hasSize(500)
        repeat(500) { line ->
            assertThat(document.state.lineForegroundColors[line]).isEqualTo(document.state.gutterDecorations[line]!!.label!!.color)
        }
        assertThat(document.state.lineForegroundColors[499]).isEqualTo(document.state.colorScheme.diagnosticError)
        assertThat(document.state.lineForegroundColors[498]).isEqualTo(document.state.colorScheme.diagnosticWarning)
        document.clear()
        assertThat(document.state.lineForegroundColors).isEmpty()
    }

    @Test
    fun selectedTextSurvivesAppendAndPrefixTrimWithoutFollowingTheTail() {
        updateMetrics()
        document.append(rows(1..500))
        val start = document.state.textBuffer.getLineStart(100)
        document.state.selectRange(start, start + 6)
        val selected = document.state.selectedText()
        document.append(rows(501..503))
        assertThat(document.state.selectedText()).isEqualTo(selected)
        assertThat(document.followingTail).isFalse()
        assertThat(document.state.selectionRange!!.start).isLessThan(start)
    }

    @Test
    fun reversedSelectionAndPartiallyExpiredTextKeepOnlyTheSurvivingRange() {
        document.append(rows(1..500))
        val end = document.state.textBuffer.getLineStart(3) - 1
        document.state.selectRange(end, 0)
        document.append(rows(501..501))
        assertThat(document.state.selectedText()).isEqualTo("row2\nrow3")
        assertThat(document.state.selectionRange!!.caret).isEqualTo(0)
        assertThat(document.state.selectionRange!!.anchor).isGreaterThan(0)
    }

    @Test
    fun selectingAtTheTailPausesAppendAndResizeFollowing() {
        updateMetrics()
        document.append(rows(1..80))
        val start = document.state.textBuffer.getLineStart(79)
        document.state.selectRange(start, start + 5)
        val scroll = document.state.scrollOffsetPx
        document.append(rows(81..90))
        document.scrollToEnd()
        assertThat(document.state.scrollOffsetPx).isEqualTo(scroll)
        assertThat(document.state.selectedText()).isEqualTo("row80")
    }

    @Test
    fun expiredSelectionClearsInsteadOfSelectingUnrelatedNewOutput() {
        document.append(rows(1..500))
        document.state.selectRange(0, 4)
        document.append(rows(501..503))
        assertThat(document.state.selectionRange).isNull()
        document.state.selectAll()
        document.clear()
        assertThat(document.state.selectionRange).isNull()
    }

    @Test
    fun defaultsUsePinnedLineNumbersWithoutWrappingFoldingOrRuntimeDiagnostics() {
        val config = document.state.config
        assertThat(config.showLineNumbers).isTrue()
        assertThat(config.pinLineNumber).isTrue()
        assertThat(config.wordWrap).isFalse()
        assertThat(config.codeFolding).isFalse()
        assertThat(config.rainbowBrackets).isFalse()
        assertThat(config.gutterMode).isEqualTo(EditorGutterMode.LABELS)
        assertThat(document.state.gutterDecorations).isEmpty()
        assertThat(document.state.runtimeOptions.layoutDiagnosticsEnabled()).isFalse()
        assertThat(document.state.runtimeOptions.imeDiagnosticsEnabled()).isFalse()
        assertThat(document.state.runtimeOptions.touchDiagnostics().enabled).isFalse()
        assertThat(document.state.isFocused).isFalse()
        assertThat(document.state.highlighter).isNull()
    }

    @Test
    fun logLevelPrefixesDoNotConsumeBodyColumns() {
        document.append(listOf(
            GraphicalRuntimeLogEntry('I', "info"),
            GraphicalRuntimeLogEntry('W', "warning"),
            GraphicalRuntimeLogEntry('E', "error"),
        ))
        assertThat(text()).isEqualTo("info\nwarning\nerror")
    }

    @Test
    fun appendingKeepsTheSameEditorBufferAndDoesNotRetainUndoHistory() {
        val state = document.state
        val buffer = state.textBuffer
        document.append(listOf(GraphicalRuntimeLogEntry('I', "第一行 😀")))
        document.append(listOf(GraphicalRuntimeLogEntry('E', "第二行")))
        assertThat(document.state).isSameInstanceAs(state)
        assertThat(state.textBuffer).isSameInstanceAs(buffer)
        assertThat(text()).isEqualTo("第一行 😀\n第二行")
        assertThat(levels()).containsExactly('I', 'E').inOrder()
        assertThat(buffer.canUndo()).isFalse()
        assertThat(buffer.canRedo()).isFalse()
        assertThat(document.revision).isEqualTo(2L)
    }

    @Test
    fun outputContinuesUpdatingAfterTheFiveHundredLineLimit() {
        document.append(rows(1..500))
        document.append(rows(501..503))
        assertThat(document.lineCount).isEqualTo(500)
        assertThat(document.state.textBuffer.getLine(0)).isEqualTo("row4")
        assertThat(document.state.textBuffer.getLine(499)).isEqualTo("row503")
        assertLabelsCoverEveryLogicalLine()
        assertThat(document.revision).isEqualTo(2L)
    }

    @Test
    fun boundedCharactersAlsoLimitManyLongLinesWithoutCuttingTheLastLine() {
        val message = "x".repeat(2048) + "末尾😀"
        document.append((1..500).map { GraphicalRuntimeLogEntry('W', message) })
        assertThat(document.state.textBuffer.length).isAtMost(GRAPHICAL_LOG_MAX_CHARACTERS)
        assertThat(document.lineCount).isAtMost(GRAPHICAL_LOG_MAX_LINES)
        assertThat(document.lineCount).isGreaterThan(1)
        assertThat(document.state.textBuffer.getLine(document.lineCount - 1)).isEqualTo(message)
        assertLabelsCoverEveryLogicalLine()
        assertThat(levels().distinct()).containsExactly('W')
    }

    @Test
    fun followingTheTailDoesNotStopWhenTheVisibleLineCountStaysConstant() {
        updateMetrics()
        document.append(rows(1..500))
        assertThat(document.state.scrollOffsetPx).isEqualTo(document.state.maxVerticalScrollOffsetPx())
        document.append(rows(501..510))
        assertThat(document.followingTail).isTrue()
        assertThat(document.state.scrollOffsetPx).isEqualTo(document.state.maxVerticalScrollOffsetPx())
    }

    @Test
    fun reviewingHistoryPreservesTheSameTopLineWhileOldLinesAreTrimmed() {
        updateMetrics()
        document.append(rows(1..500))
        document.state.scrollToLine(100)
        document.onUserScroll()
        val previousTopLine = document.state.textBuffer.getLine(100)
        document.append(rows(501..501))
        val topLine = (document.state.scrollOffsetPx / document.state.lineHeightPx).toInt()
        assertThat(document.followingTail).isFalse()
        assertThat(document.state.textBuffer.getLine(topLine)).isEqualTo(previousTopLine)
    }

    @Test
    fun wrappingCanBeToggledWithoutReplacingTheDocumentOrChangingOtherEditorOptions() {
        updateMetrics()
        document.append(listOf(GraphicalRuntimeLogEntry('I', "x".repeat(1000))))
        val buffer = document.state.textBuffer
        document.state.scrollByX(100f)
        assertThat(document.state.scrollOffsetXPx).isGreaterThan(0f)
        document.setWordWrap(true)
        assertThat(document.state.config.wordWrap).isTrue()
        assertThat(document.state.maxHorizontalScrollOffsetPx()).isEqualTo(0f)
        assertThat(document.state.scrollOffsetXPx).isEqualTo(0f)
        document.setWordWrap(false)
        assertThat(document.state.maxHorizontalScrollOffsetPx()).isGreaterThan(0f)
        assertThat(document.state.textBuffer).isSameInstanceAs(buffer)
        assertThat(document.state.config.showLineNumbers).isTrue()
        assertThat(document.state.config.gutterMode).isEqualTo(EditorGutterMode.LABELS)
        assertThat(levels()).containsExactly('I')
        assertThat(document.state.runtimeOptions.layoutDiagnosticsEnabled()).isFalse()
    }

    @Test
    fun clearingResetsBothScrollAxesButPreservesTheWrapChoice() {
        updateMetrics()
        document.append(rows(1..200))
        document.setWordWrap(true)
        document.clear()
        assertThat(document.lineCount).isEqualTo(0)
        assertThat(text()).isEmpty()
        assertThat(document.state.gutterDecorations).isEmpty()
        assertThat(document.state.scrollOffsetPx).isEqualTo(0f)
        assertThat(document.state.scrollOffsetXPx).isEqualTo(0f)
        assertThat(document.state.config.wordWrap).isTrue()
        assertThat(document.followingTail).isTrue()
        document.append(listOf(GraphicalRuntimeLogEntry('I', "new output")))
        assertThat(document.lineCount).isEqualTo(1)
    }

    @Test
    fun crlfAndUnicodeRemainIntactAndPhysicalLinesAreBounded() {
        document.append(listOf(GraphicalRuntimeLogEntry('I', "你好😀\r\n下一行\r末行")))
        assertThat(text()).isEqualTo("你好😀\n下一行\n末行")
        assertThat(document.lineCount).isEqualTo(3)
        assertThat(levels()).containsExactly('I', 'I', 'I').inOrder()
        document.append(listOf(GraphicalRuntimeLogEntry('I', (1..600).joinToString("\n") { "line$it" })))
        assertThat(document.lineCount).isEqualTo(500)
        assertThat(document.state.textBuffer.getLine(499)).isEqualTo("line600")
        assertLabelsCoverEveryLogicalLine()
    }

    @Test
    fun thePerMessageLimitDoesNotSplitASurrogatePair() {
        val prefix = "x".repeat(GRAPHICAL_LOG_MAX_LINE_CHARS - 1)
        document.append(listOf(GraphicalRuntimeLogEntry('I', prefix + "😀tail")))
        assertThat(text()).isEqualTo(prefix)
        assertThat(text().last().isSurrogate()).isFalse()
    }

    @Test
    fun everyPriorityUsesAColoredLabelInsteadOfABreakpointDecoration() {
        val priorities = listOf('V', 'D', 'I', 'W', 'E', 'F')
        document.append(priorities.map { GraphicalRuntimeLogEntry(it, "message $it") })
        assertThat(levels()).containsExactlyElementsIn(priorities).inOrder()
        val scheme = document.state.colorScheme
        assertThat(document.state.gutterDecorations[2]?.label?.color).isEqualTo(scheme.diagnosticInfo)
        assertThat(document.state.gutterDecorations[3]?.label?.color).isEqualTo(scheme.diagnosticWarning)
        assertThat(document.state.gutterDecorations[4]?.label?.color).isEqualTo(scheme.diagnosticError)
        assertThat(document.state.gutterDecorations[5]?.label?.color).isEqualTo(scheme.diagnosticError)
        document.state.gutterDecorations.values.forEach {
            assertThat(it.breakpoint || it.bookmark || it.foldable || it.hasDiagnostic).isFalse()
        }
    }

    @Test
    fun multilineAndTrailingBlankLinesInheritTheCorrectMessageLevel() {
        document.append(listOf(
            GraphicalRuntimeLogEntry('W', "first\r\nsecond\rthird\n"),
            GraphicalRuntimeLogEntry('E', "last"),
        ))
        assertThat(text()).isEqualTo("first\nsecond\nthird\n\nlast")
        assertThat(levels()).containsExactly('W', 'W', 'W', 'W', 'E').inOrder()
        assertLabelsCoverEveryLogicalLine()
    }

    @Test
    fun emptyMessagesRemainDistinctAcrossAppendBatches() {
        document.append(listOf(GraphicalRuntimeLogEntry('I', "")))
        assertThat(document.lineCount).isEqualTo(1)
        assertThat(text()).isEmpty()
        assertThat(levels()).containsExactly('I')
        document.append(listOf(GraphicalRuntimeLogEntry('W', ""), GraphicalRuntimeLogEntry('E', "last")))
        assertThat(text()).isEqualTo("\n\nlast")
        assertThat(levels()).containsExactly('I', 'W', 'E').inOrder()
        assertLabelsCoverEveryLogicalLine()
    }

    @Test
    fun trimmingRebasesOldLabelsAndAddsEachNewMultilineLabel() {
        document.append((0..499).map { GraphicalRuntimeLogEntry(if (it % 2 == 0) 'I' else 'W', "row$it") })
        val survivingDecoration = document.state.gutterDecorations[2]
        document.append(listOf(GraphicalRuntimeLogEntry('F', "fatal\ntrace")))
        assertThat(document.state.textBuffer.getLine(0)).isEqualTo("row2")
        assertThat(document.state.gutterDecorations[0]).isSameInstanceAs(survivingDecoration)
        assertThat(levels().take(4)).containsExactly('I', 'W', 'I', 'W').inOrder()
        assertThat(levels().takeLast(2)).containsExactly('F', 'F').inOrder()
        assertLabelsCoverEveryLogicalLine()
    }

    @Test
    fun characterBudgetKeepsLevelsAlignedWhenItTrimsMoreThanTheLineBudget() {
        document.append((0..499).map { GraphicalRuntimeLogEntry(if (it % 2 == 0) 'W' else 'E', "$it:" + "x".repeat(4096)) })
        assertThat(document.state.textBuffer.length).isAtMost(GRAPHICAL_LOG_MAX_CHARACTERS)
        assertThat(document.lineCount).isLessThan(GRAPHICAL_LOG_MAX_LINES)
        repeat(document.lineCount) { line ->
            val originalIndex = document.state.textBuffer.getLine(line).substringBefore(':').toInt()
            assertThat(document.state.gutterDecorations[line]?.label?.character).isEqualTo(if (originalIndex % 2 == 0) 'W' else 'E')
        }
        assertLabelsCoverEveryLogicalLine()
    }

    @Test
    fun newlineHeavyMessagesKeepOnlyBoundedMetadataAndContinueUpdating() {
        document.append(listOf(GraphicalRuntimeLogEntry('W', "\n".repeat(GRAPHICAL_LOG_MAX_LINE_CHARS))))
        assertThat(document.lineCount).isEqualTo(GRAPHICAL_LOG_MAX_LINES)
        assertThat(levels().distinct()).containsExactly('W')
        assertLabelsCoverEveryLogicalLine()
        document.append(listOf(GraphicalRuntimeLogEntry('I', "after")))
        assertThat(document.state.textBuffer.getLine(499)).isEqualTo("after")
        assertThat(levels().last()).isEqualTo('I')
        assertLabelsCoverEveryLogicalLine()
    }

    @Test
    fun clearingAndAppendingCannotReuseAnOldLevel() {
        document.append(listOf(GraphicalRuntimeLogEntry('E', "old\ntrace")))
        document.clear()
        document.append(listOf(GraphicalRuntimeLogEntry('I', "new")))
        assertThat(text()).isEqualTo("new")
        assertThat(levels()).containsExactly('I')
        assertLabelsCoverEveryLogicalLine()
    }

    @Test
    fun logGutterActionsDoNotDispatchBreakpointFoldingOrLongPressCallbacks() {
        var callbacks = 0
        document.state.onGutterTap = { callbacks++ }
        document.state.onGutterFoldToggle = { callbacks++ }
        document.state.onGutterLongPress = { callbacks++ }
        document.append(listOf(GraphicalRuntimeLogEntry('I', "line")))
        document.state.dispatchGutterTap(0)
        document.state.dispatchGutterFoldToggle(0)
        document.state.dispatchGutterLongPress(0)
        assertThat(callbacks).isEqualTo(0)
    }

    @Test
    fun closeIsIdempotentAndReleasesTheOwnedBuffer() {
        document.close()
        document.close()
        assertThat(document.state.textBuffer.isClosed).isTrue()
        assertThrows(IllegalStateException::class.java) { document.append(rows(1..1)) }
    }

    private fun rows(range: IntRange) = range.map { GraphicalRuntimeLogEntry('I', "row$it") }
    private fun levels() = (0 until document.lineCount).map { document.state.gutterDecorations[it]?.label?.character }
    private fun assertLabelsCoverEveryLogicalLine() {
        assertThat(document.state.gutterDecorations.keys).containsExactlyElementsIn(0 until document.lineCount)
        assertThat(levels()).doesNotContain(null)
    }
    private fun text() = document.state.textBuffer.run { substring(0, length) }
    private fun updateMetrics() = document.state.updateMetrics(10f, 5f, 100f, 200f, 0f)
}
