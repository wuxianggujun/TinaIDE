package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TextRenderPlannerTest {

    @Test
    fun buildRuns_shouldUseDefaultSyntaxAndSemanticPriority() {
        val runs = TextRenderPlanner.buildRuns(
            visibleStartColumn = 0,
            visibleEndColumn = 10,
            defaultColor = 1,
            syntaxOverlays = listOf(
                TextRenderOverlay(startColumn = 2, endColumn = 6, color = 2)
            ),
            semanticOverlays = listOf(
                TextRenderOverlay(startColumn = 4, endColumn = 8, color = 3)
            )
        )

        assertThat(runs).containsExactly(
            TextRenderRun(startColumn = 0, endColumn = 2, color = 1),
            TextRenderRun(startColumn = 2, endColumn = 4, color = 2),
            TextRenderRun(startColumn = 4, endColumn = 8, color = 3),
            TextRenderRun(startColumn = 8, endColumn = 10, color = 1)
        ).inOrder()
    }

    @Test
    fun buildRuns_shouldKeepCommentColorOverStaleSemanticTokens() {
        val runs = TextRenderPlanner.buildRuns(
            visibleStartColumn = 0,
            visibleEndColumn = 12,
            defaultColor = 1,
            syntaxOverlays = listOf(
                TextRenderOverlay(
                    startColumn = 0,
                    endColumn = 12,
                    color = 2,
                    blocksSemantic = true,
                ),
                TextRenderOverlay(startColumn = 4, endColumn = 6, color = 4),
            ),
            semanticOverlays = listOf(
                TextRenderOverlay(startColumn = 3, endColumn = 9, color = 3),
            ),
        )

        assertThat(runs).containsExactly(
            TextRenderRun(startColumn = 0, endColumn = 12, color = 2),
        )
    }

    @Test
    fun buildRuns_shouldMergeAdjacentRunsWithSameColor() {
        val runs = TextRenderPlanner.buildRuns(
            visibleStartColumn = 0,
            visibleEndColumn = 10,
            defaultColor = 1,
            syntaxOverlays = listOf(
                TextRenderOverlay(startColumn = 2, endColumn = 4, color = 2),
                TextRenderOverlay(startColumn = 4, endColumn = 6, color = 2)
            ),
            semanticOverlays = emptyList()
        )

        assertThat(runs).containsExactly(
            TextRenderRun(startColumn = 0, endColumn = 2, color = 1),
            TextRenderRun(startColumn = 2, endColumn = 6, color = 2),
            TextRenderRun(startColumn = 6, endColumn = 10, color = 1)
        ).inOrder()
    }

    @Test
    fun buildRuns_shouldPreferSpecificSyntaxColorOverVariableColor() {
        val runs = TextRenderPlanner.buildRuns(
            visibleStartColumn = 0,
            visibleEndColumn = 6,
            defaultColor = 1,
            syntaxOverlays = listOf(
                TextRenderOverlay(startColumn = 0, endColumn = 6, color = 2),
                TextRenderOverlay(startColumn = 0, endColumn = 6, color = 3)
            ),
            semanticOverlays = emptyList()
        )

        assertThat(runs).containsExactly(
            TextRenderRun(startColumn = 0, endColumn = 6, color = 3)
        )
    }

    @Test
    fun clampTextDrawRange_shouldTrimEndBeyondLineLength() {
        val range = clampTextDrawRange(
            textLength = 5,
            startColumn = 2,
            endColumn = 9
        )

        assertThat(range).isEqualTo(TextDrawRange(startColumn = 2, endColumn = 5))
    }

    @Test
    fun clampTextDrawRange_shouldDropRangesOutsideLineLength() {
        val range = clampTextDrawRange(
            textLength = 4,
            startColumn = 7,
            endColumn = 10
        )

        assertThat(range).isNull()
    }
}
