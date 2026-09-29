package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import org.junit.Test

class SignatureHelpContextResolverTest {

    @Test
    fun hasActiveSignatureHelpContext_shouldDetectOpenCallParenthesis() {
        assertThat(hasContext("print(|")).isTrue()
        assertThat(hasContext("print(value, na|me)")).isTrue()
        assertThat(hasContext("outer(inner(1), |)")).isTrue()
    }

    @Test
    fun hasActiveSignatureHelpContext_shouldReturnFalseAfterClosingParenthesis() {
        assertThat(hasContext("print(value)|")).isFalse()
        assertThat(hasContext("print(\"(\")|")).isFalse()
    }

    @Test
    fun hasActiveSignatureHelpContext_shouldIgnoreCommentContent() {
        assertThat(hasContext("print(value, // )\n|")).isTrue()
        assertThat(hasContext("print(value /* ) */, |)")).isTrue()
        assertThat(hasContext("print(value) // (\n|")).isFalse()
        assertThat(hasContext("print(value) /* ( */\n|")).isFalse()
    }

    @Test
    fun hasActiveSignatureHelpContext_shouldIgnoreEscapedAndRawStrings() {
        assertThat(hasContext("print(\"value \\\" ) still string\", |)")).isTrue()
        assertThat(hasContext("print(\"\"\"value \" ) still raw\"\"\", |)")).isTrue()
        assertThat(hasContext("print(\"\"\"value \" ) still raw\"\"\")|")).isFalse()
    }

    @Test
    fun hasActiveSignatureHelpContext_shouldHandleGenericCalls() {
        assertThat(hasContext("factory<Map<String, List<Int>>>(value, |)")).isTrue()
    }

    @Test
    fun hasActiveSignatureHelpContext_shouldTreatTrailingLambdaAsActiveCallContext() {
        assertThat(hasContext("fold(0) { acc, value -> | }")).isTrue()
        assertThat(hasContext("runCatching { | }")).isTrue()
        assertThat(hasContext("fold(0) { acc, value -> if (value > 0) { | } else value }")).isTrue()
    }

    @Test
    fun hasActiveSignatureHelpContext_shouldNotTreatControlBlocksAsTrailingLambdaContext() {
        assertThat(hasContext("if (ready) { | }")).isFalse()
        assertThat(hasContext("while (ready) { | }")).isFalse()
        assertThat(hasContext("fold(0) { acc, value -> value }|")).isFalse()
    }

    @Test
    fun resolveSignatureHelpAutoRefreshAction_shouldRefreshOnlyForVisibleActiveContext() {
        assertThat(
            resolveSignatureHelpAutoRefreshAction(
                isVisible = true,
                hasSelection = false,
                hasActiveContext = true,
                allowAutoRefresh = true,
                suppressForRecentEdit = false
            )
        ).isEqualTo(SignatureHelpAutoRefreshAction.Refresh)

        assertThat(
            resolveSignatureHelpAutoRefreshAction(
                isVisible = true,
                hasSelection = false,
                hasActiveContext = true,
                allowAutoRefresh = true,
                suppressForRecentEdit = true
            )
        ).isEqualTo(SignatureHelpAutoRefreshAction.Ignore)

        assertThat(
            resolveSignatureHelpAutoRefreshAction(
                isVisible = true,
                hasSelection = false,
                hasActiveContext = false,
                allowAutoRefresh = true,
                suppressForRecentEdit = false
            )
        ).isEqualTo(SignatureHelpAutoRefreshAction.Dismiss)
    }

    private fun hasContext(markedText: String): Boolean {
        val cursorOffset = markedText.indexOf('|')
        check(cursorOffset >= 0) { "cursor marker '|' is required" }
        val buffer = RopeTextBuffer().apply {
            insert(0, markedText.replace("|", ""))
        }
        return hasActiveSignatureHelpContext(buffer, cursorOffset)
    }
}
