package com.wuxianggujun.tinaide.ui.compose.state.editor

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EditorActionsStateTest {

    @Test
    fun dismissCodeActions_allowsClosingWhileRequestIsLoading() {
        val state = EditorActionsState().apply {
            showCodeActionsMenu = true
            codeActionsLoading = true
        }

        state.dismissCodeActions()

        assertThat(state.showCodeActionsMenu).isFalse()
    }
}
