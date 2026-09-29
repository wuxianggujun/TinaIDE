package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SignatureHelpVisibleSliceResolverTest {

    @Test
    fun resolveSignatureHelpVisibleSlice_shouldReturnAllItemsWhenCountFits() {
        val slice = resolveSignatureHelpVisibleSlice(
            totalCount = 3,
            selectedIndex = 1,
            maxVisibleItems = 4
        )

        assertThat(slice).isEqualTo(
            SignatureHelpVisibleSlice(
                startIndex = 0,
                endExclusive = 3,
                hiddenBefore = 0,
                hiddenAfter = 0
            )
        )
    }

    @Test
    fun resolveSignatureHelpVisibleSlice_shouldCenterAroundSelectionWhenPossible() {
        val slice = resolveSignatureHelpVisibleSlice(
            totalCount = 8,
            selectedIndex = 4,
            maxVisibleItems = 3
        )

        assertThat(slice).isEqualTo(
            SignatureHelpVisibleSlice(
                startIndex = 3,
                endExclusive = 6,
                hiddenBefore = 3,
                hiddenAfter = 2
            )
        )
    }

    @Test
    fun resolveSignatureHelpVisibleSlice_shouldClampToStartAndEndEdges() {
        val nearStart = resolveSignatureHelpVisibleSlice(
            totalCount = 8,
            selectedIndex = 0,
            maxVisibleItems = 3
        )
        val nearEnd = resolveSignatureHelpVisibleSlice(
            totalCount = 8,
            selectedIndex = 7,
            maxVisibleItems = 3
        )

        assertThat(nearStart).isEqualTo(
            SignatureHelpVisibleSlice(
                startIndex = 0,
                endExclusive = 3,
                hiddenBefore = 0,
                hiddenAfter = 5
            )
        )
        assertThat(nearEnd).isEqualTo(
            SignatureHelpVisibleSlice(
                startIndex = 5,
                endExclusive = 8,
                hiddenBefore = 5,
                hiddenAfter = 0
            )
        )
    }

    @Test
    fun resolveSignatureHelpVisibleSlice_shouldClampInvalidInput() {
        val slice = resolveSignatureHelpVisibleSlice(
            totalCount = 5,
            selectedIndex = 99,
            maxVisibleItems = 0
        )

        assertThat(slice).isEqualTo(
            SignatureHelpVisibleSlice(
                startIndex = 4,
                endExclusive = 5,
                hiddenBefore = 4,
                hiddenAfter = 0
            )
        )
    }
}
