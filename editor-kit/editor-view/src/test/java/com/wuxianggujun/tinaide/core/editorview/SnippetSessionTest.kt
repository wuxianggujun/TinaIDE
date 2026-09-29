package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SnippetSessionTest {

    @Test
    fun `session groups repeated tabstops and navigates to final cursor`() {
        val session = SnippetSession(
            baseOffset = 10,
            parsed = parseSnippet("const \${1:name} = \${1:name};\n\$0")
        )

        assertThat(session.currentGroup().map { it.tabstopIndex })
            .containsExactly(1, 1)
            .inOrder()
        assertThat(session.currentGroup().map { it.offsetInText })
            .containsExactly(6, 13)
            .inOrder()
        assertThat(session.currentPlaceholder()?.length).isEqualTo(4)
        assertThat(session.absoluteOffsetOf(session.currentPlaceholder()!!)).isEqualTo(16)

        val finalStop = session.advance()
        assertThat(finalStop).isNotNull()
        assertThat(finalStop!!.currentPlaceholder()?.tabstopIndex).isEqualTo(0)
        assertThat(finalStop.currentPlaceholder()?.offsetInText).isEqualTo(19)

        assertThat(finalStop.advance()).isNull()
        assertThat(finalStop.retreat()?.currentPlaceholder()?.tabstopIndex).isEqualTo(1)
    }

    @Test
    fun `session adjustOffsets updates active length and following offsets`() {
        val session = SnippetSession(
            baseOffset = 100,
            parsed = parseSnippet("foo \${1:name} bar \${2:value}")
        )

        val afterInsert = session.adjustOffsets(editOffset = 106, delta = 2)
        assertThat(afterInsert.currentPlaceholder()?.length).isEqualTo(6)
        assertThat(afterInsert.parsed.placeholders.map { it.offsetInText })
            .containsExactly(4, 15)
            .inOrder()

        val afterDelete = afterInsert.adjustOffsets(editOffset = 104, delta = -1)
        assertThat(afterDelete.currentPlaceholder()?.length).isEqualTo(5)
        assertThat(afterDelete.parsed.placeholders.map { it.offsetInText })
            .containsExactly(4, 14)
            .inOrder()
    }
}
