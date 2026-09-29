package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import org.junit.Test

class RainbowBracketComputerTest {

    @Test
    fun computeVisibleLineBrackets_shouldReturnEmptyWhenFeatureDisabled() {
        val buffer = RopeTextBuffer().apply {
            insert(0, "{\n  call(foo)\n}\n")
        }
        val computer = RainbowBracketComputer()
        val frameContext = createFrameContext(buffer)

        val brackets = computer.computeVisibleLineBrackets(
            frameContext = frameContext,
            visibleLines = 0..2,
            config = EditorConfig(rainbowBrackets = false)
        )

        assertThat(brackets).isEmpty()
    }

    @Test
    fun computeVisibleLineBrackets_shouldReturnEmptyWhenLineCountExceedsLimit() {
        val buffer = RopeTextBuffer().apply {
            insert(0, "{\n  call(foo)\n}\n")
        }
        val computer = RainbowBracketComputer()
        val frameContext = createFrameContext(buffer)

        val brackets = computer.computeVisibleLineBrackets(
            frameContext = frameContext,
            visibleLines = 0..2,
            config = EditorConfig(
                rainbowBrackets = true,
                rainbowBracketsMaxLines = 2
            )
        )

        assertThat(brackets).isEmpty()
    }

    @Test
    fun computeVisibleLineBrackets_shouldDelegateToSharedSnapshotCache() {
        val buffer = RopeTextBuffer().apply {
            insert(0, "{\n  call(foo)\n}\n")
        }
        val computer = RainbowBracketComputer()
        val frameContext = createFrameContext(buffer)

        val brackets = computer.computeVisibleLineBrackets(
            frameContext = frameContext,
            visibleLines = 0..2,
            config = EditorConfig(rainbowBrackets = true, rainbowBracketsMaxLines = 0)
        )

        assertThat(brackets[1]).containsExactly(
            RainbowBracketComputer.BracketInfo(column = 6, depth = 1, isOpen = true),
            RainbowBracketComputer.BracketInfo(column = 10, depth = 1, isOpen = false)
        ).inOrder()
    }

    private fun createFrameContext(buffer: RopeTextBuffer): EditorRenderFrameContext {
        val state = EditorState(buffer)
        return EditorRenderFrameContext(
            state = state,
            textVersion = buffer.version,
            textScanCache = EditorTextScanCache(),
            bracketSnapshotCache = EditorBracketSnapshotCache(),
            lineTextProvider = buffer::getLine
        )
    }
}
