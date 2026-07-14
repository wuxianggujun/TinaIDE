package com.wuxianggujun.tinaide.ui.compose.state.editor

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PluginLspOwnerStopHandlerTest {
    @Test
    fun `current attachment is released and transitions to no lsp`() {
        val token = Any()
        var released = false
        var noLsp = false
        val handler = PluginLspOwnerStopHandler(
            expectedAttachToken = token,
            currentAttachToken = { token },
            releaseSession = { released = true },
            markNoLsp = { noLsp = true },
        )

        assertThat(handler.handle()).isTrue()
        assertThat(released).isTrue()
        assertThat(noLsp).isTrue()
    }

    @Test
    fun `stale attachment cannot release replacement session`() {
        val handler = PluginLspOwnerStopHandler(
            expectedAttachToken = Any(),
            currentAttachToken = { Any() },
            releaseSession = { throw AssertionError("replacement session released") },
            markNoLsp = { throw AssertionError("replacement status changed") },
        )

        assertThat(handler.handle()).isFalse()
    }
}
