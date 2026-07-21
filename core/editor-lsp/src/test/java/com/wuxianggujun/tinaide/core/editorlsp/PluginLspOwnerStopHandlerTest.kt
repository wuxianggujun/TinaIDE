package com.wuxianggujun.tinaide.core.editorlsp

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PluginLspOwnerStopHandlerTest {
    @Test
    fun `current attachment is released and transitions to no lsp`() {
        var transitioned = false
        val handler = PluginLspOwnerStopHandler(
            transitionIfCurrent = {
                transitioned = true
                true
            },
        )

        assertThat(handler.handle()).isTrue()
        assertThat(transitioned).isTrue()
    }

    @Test
    fun `stale attachment cannot release replacement session`() {
        val handler = PluginLspOwnerStopHandler(
            transitionIfCurrent = { false },
        )

        assertThat(handler.handle()).isFalse()
    }
}
