package com.wuxianggujun.tinaide.core.editorlsp

import com.google.common.truth.Truth.assertThat
import org.eclipse.lsp4j.InlayHint as LspInlayHint
import org.eclipse.lsp4j.InlayHintKind as LspInlayHintKind
import org.eclipse.lsp4j.InlayHintLabelPart
import org.eclipse.lsp4j.Position
import org.junit.Test

class InlayHintModelsTest {
    @Test
    fun toEditorInlayHint_shouldKeepParameterLabelAndPadding() {
        val source = LspInlayHint().apply {
            position = Position(3, 7)
            setLabel("value:")
            kind = LspInlayHintKind.Parameter
            paddingLeft = true
            paddingRight = false
        }

        assertThat(source.toEditorInlayHintOrNull()).isEqualTo(
            InlayHint(
                line = 3,
                column = 7,
                label = "value:",
                kind = InlayHintKind.PARAMETER,
                paddingLeft = true,
                paddingRight = false,
            )
        )
    }

    @Test
    fun toEditorInlayHint_shouldJoinTypeLabelParts() {
        val source = LspInlayHint().apply {
            position = Position(1, 12)
            setLabel(listOf(InlayHintLabelPart(": "), InlayHintLabelPart("Widget")))
            kind = LspInlayHintKind.Type
            paddingRight = true
        }

        val mapped = source.toEditorInlayHintOrNull()

        assertThat(mapped?.label).isEqualTo(": Widget")
        assertThat(mapped?.kind).isEqualTo(InlayHintKind.TYPE)
        assertThat(mapped?.paddingRight).isTrue()
    }

    @Test
    fun expandInlayHintRequestLines_shouldPrefetchAndCapRange() {
        assertThat(expandInlayHintRequestLines(100..120)).isEqualTo(76..144)
        assertThat(expandInlayHintRequestLines(0..500)).isEqualTo(0..240)
    }
}
