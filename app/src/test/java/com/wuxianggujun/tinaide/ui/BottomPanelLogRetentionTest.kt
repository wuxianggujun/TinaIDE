package com.wuxianggujun.tinaide.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BottomPanelLogRetentionTest {
    @Test
    fun retainLatestLogs_keepsNewestEntriesAcrossBatches() {
        val retained = retainLatestLogs(
            current = listOf(1, 2, 3),
            incoming = listOf(4, 5),
            limit = 4
        )

        assertThat(retained).containsExactly(2, 3, 4, 5).inOrder()
    }

    @Test
    fun retainLatestLogs_capsOversizedIncomingBatch() {
        val retained = retainLatestLogs(
            current = listOf(1, 2),
            incoming = listOf(3, 4, 5, 6),
            limit = 3
        )

        assertThat(retained).containsExactly(4, 5, 6).inOrder()
    }
}
