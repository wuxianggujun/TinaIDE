package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.editorlsp.SignatureHelpResult
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class EditorSignatureHelpStateTest {

    @Test
    fun requestSignatureHelp_shouldExposeVisibleResult() = runTest {
        val buffer = RopeTextBuffer().apply { insert(0, "print(") }
        val state = EditorState(buffer)
        state.moveCursorTo(buffer.length)
        state.onRequestSignatureHelp = {
            SignatureHelpResult(
                signatures = listOf("print(String text, Int repeatCount)"),
                activeSignature = 0,
                activeParameter = 1
            )
        }

        state.requestSignatureHelp()

        val uiState = state.signatureHelpUiState
        assertThat(uiState).isInstanceOf(SignatureHelpUiState.Visible::class.java)
        uiState as SignatureHelpUiState.Visible
        assertThat(uiState.result.signatures).containsExactly("print(String text, Int repeatCount)")
        assertThat(uiState.result.activeSignature).isEqualTo(0)
        assertThat(uiState.result.activeParameter).isEqualTo(1)
    }

    @Test
    fun requestSignatureHelp_shouldHideWhenProviderReturnsNull() = runTest {
        val state = EditorState(RopeTextBuffer())
        state.onRequestSignatureHelp = { null }

        state.requestSignatureHelp()

        assertThat(state.signatureHelpUiState).isEqualTo(SignatureHelpUiState.Hidden)
    }

    @Test
    fun dismissSignatureHelp_shouldResetToHidden() {
        val state = EditorState(RopeTextBuffer())
        state.seedVisibleSignatureHelp(
            result = SignatureHelpResult(
                signatures = listOf("foo(Int value)"),
                activeSignature = 0,
                activeParameter = 0
            ),
            requestId = 1L,
            selectedIndex = 0
        )

        state.dismissSignatureHelp()

        assertThat(state.signatureHelpUiState).isEqualTo(SignatureHelpUiState.Hidden)
        assertThat(state.signatureHelpSelectedSignatureIndex).isNull()
    }

    @Test
    fun cycleSignatureHelp_shouldRotateThroughAvailableOverloads() {
        val state = EditorState(RopeTextBuffer())
        state.seedVisibleSignatureHelp(
            result = SignatureHelpResult(
                signatures = listOf(
                    "foo(Int value)",
                    "foo(String value)",
                    "foo(Int value, Boolean enabled)"
                ),
                activeSignature = 1,
                activeParameter = 0
            ),
            requestId = 1L
        )

        assertThat(state.resolveDisplayedSignatureHelpIndex((state.signatureHelpUiState as SignatureHelpUiState.Visible).result))
            .isEqualTo(1)

        assertThat(state.cycleSignatureHelp(1)).isTrue()
        assertThat(state.signatureHelpSelectedSignatureIndex).isEqualTo(2)

        assertThat(state.cycleSignatureHelp(1)).isTrue()
        assertThat(state.signatureHelpSelectedSignatureIndex).isEqualTo(0)

        assertThat(state.cycleSignatureHelp(-1)).isTrue()
        assertThat(state.signatureHelpSelectedSignatureIndex).isEqualTo(2)
    }

    @Test
    fun selectSignatureHelp_shouldIgnoreOutOfRangeIndex() {
        val state = EditorState(RopeTextBuffer())
        state.seedVisibleSignatureHelp(
            result = SignatureHelpResult(
                signatures = listOf("foo(Int value)", "foo(String value)"),
                activeSignature = 0,
                activeParameter = 0
            ),
            requestId = 1L
        )

        assertThat(state.selectSignatureHelp(1)).isTrue()
        assertThat(state.signatureHelpSelectedSignatureIndex).isEqualTo(1)

        assertThat(state.selectSignatureHelp(99)).isFalse()
        assertThat(state.signatureHelpSelectedSignatureIndex).isEqualTo(1)
    }

    @Test
    fun requestSignatureHelp_shouldClampSelectedSignatureIndexWhenResultShrinks() = runTest {
        val state = EditorState(RopeTextBuffer())
        state.seedVisibleSignatureHelp(
            result = SignatureHelpResult(
                signatures = listOf(
                    "foo(Int count, String label)",
                    "foo(Int count, Boolean enabled)",
                    "foo(Int count, Map<String, List<Int>> metadata)"
                ),
                activeSignature = 2,
                activeParameter = 1
            ),
            requestId = 1L,
            selectedIndex = 2
        )
        state.onRequestSignatureHelp = {
            SignatureHelpResult(
                signatures = listOf("foo(Int count, String label)"),
                activeSignature = 0,
                activeParameter = 1
            )
        }

        state.requestSignatureHelp()

        val uiState = state.signatureHelpUiState
        assertThat(uiState).isInstanceOf(SignatureHelpUiState.Visible::class.java)
        assertThat(state.signatureHelpSelectedSignatureIndex).isEqualTo(0)
        assertThat(resolveDisplayedSignaturePreview(state)).isEqualTo("String label")
    }

    @Test
    fun cycleSignatureHelp_shouldReusePreviousResultWhileLoading() {
        val state = EditorState(RopeTextBuffer())
        state.seedLoadingSignatureHelp(
            previousResult = SignatureHelpResult(
                signatures = listOf(
                    "foo(Int value)",
                    "foo(String value)",
                    "foo(Boolean value)"
                ),
                activeSignature = 0,
                activeParameter = 0
            ),
            requestId = 2L
        )

        assertThat(state.cycleSignatureHelp(1)).isTrue()
        assertThat(state.signatureHelpSelectedSignatureIndex).isEqualTo(1)
        assertThat(state.selectSignatureHelp(2)).isTrue()
        assertThat(state.signatureHelpSelectedSignatureIndex).isEqualTo(2)
    }

    @Test
    fun displayedSignaturePreview_shouldReusePreviousResultWhileLoading() {
        val state = EditorState(RopeTextBuffer())
        state.seedLoadingSignatureHelp(
            previousResult = SignatureHelpResult(
                signatures = listOf(
                    "foo(Int count, String label)",
                    "foo(Int count, Boolean enabled)",
                    "foo(Int count, Map<String, List<Int>> metadata)"
                ),
                activeSignature = 0,
                activeParameter = 1
            ),
            requestId = 4L
        )

        assertThat(resolveDisplayedSignaturePreview(state)).isEqualTo("String label")

        assertThat(state.cycleSignatureHelp(1)).isTrue()
        assertThat(resolveDisplayedSignaturePreview(state)).isEqualTo("Boolean enabled")

        assertThat(state.selectSignatureHelp(2)).isTrue()
        assertThat(resolveDisplayedSignaturePreview(state))
            .isEqualTo("Map<String, List<Int>> metadata")
    }

    @Test
    fun displayedSignaturePreview_shouldFollowSelectionChangesAcrossOverloads() {
        val state = EditorState(RopeTextBuffer())
        state.seedVisibleSignatureHelp(
            result = SignatureHelpResult(
                signatures = listOf(
                    "foo(Int count, String label)",
                    "foo(Int count, Boolean enabled)",
                    "foo(Int count, Map<String, List<Int>> metadata)"
                ),
                activeSignature = 0,
                activeParameter = 1
            ),
            requestId = 3L
        )

        assertThat(resolveDisplayedSignaturePreview(state)).isEqualTo("String label")

        assertThat(state.cycleSignatureHelp(1)).isTrue()
        assertThat(resolveDisplayedSignaturePreview(state)).isEqualTo("Boolean enabled")

        assertThat(state.selectSignatureHelp(2)).isTrue()
        assertThat(resolveDisplayedSignaturePreview(state))
            .isEqualTo("Map<String, List<Int>> metadata")
    }

    @Test
    fun requestSignatureHelp_shouldPropagateCancellationAndClearLoadingState() = runTest {
        val state = EditorState(RopeTextBuffer("foo("))
        state.onRequestSignatureHelp = { throw CancellationException("cancelled") }
        var cancellationPropagated = false

        try {
            state.requestSignatureHelp()
        } catch (_: CancellationException) {
            cancellationPropagated = true
        }

        assertThat(cancellationPropagated).isTrue()
        assertThat(state.signatureHelpUiState).isEqualTo(SignatureHelpUiState.Hidden)
    }

    private fun resolveDisplayedSignaturePreview(state: EditorState): String? {
        val result = when (val uiState = state.signatureHelpUiState) {
            is SignatureHelpUiState.Visible -> uiState.result
            is SignatureHelpUiState.Loading -> uiState.previousResult
            SignatureHelpUiState.Hidden -> return null
        } ?: return null
        val displayedIndex = state.resolveDisplayedSignatureHelpIndex(result)
        return resolveSignatureActiveParameterPreview(
            signature = result.signatures[displayedIndex],
            activeParameter = result.activeParameter
        )
    }
}
