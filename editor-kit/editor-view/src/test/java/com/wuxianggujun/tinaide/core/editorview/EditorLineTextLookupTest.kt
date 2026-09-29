package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import org.junit.Test

class EditorLineTextLookupTest {

    @Test
    fun lineText_shouldRefreshAfterTextVersionChanges() {
        val buffer = RopeTextBuffer().apply {
            insert(0, "alpha\nbeta")
        }
        val state = EditorState(buffer)
        val lookup = EditorLineTextLookup(state, maxCacheSize = 2)

        assertThat(lookup.lineText(0)).isEqualTo("alpha")

        buffer.insert(0, "!")

        assertThat(lookup.lineText(0)).isEqualTo("!alpha")
    }
}
