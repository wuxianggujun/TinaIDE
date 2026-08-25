package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class EditorClipboardBridgeTest {

    @Test
    fun multilineCommitBelowTruncationLimit_shouldNotRestoreCachedPrefix() {
        val cached = "line one\nline two\n" + "x".repeat(700)
        val legitimateCommit = "line one\nline two\n"
        EditorClipboardBridge.rememberCopiedText(cached)

        val recovered = EditorClipboardBridge.recoverPossiblyTruncatedImeCommitText(legitimateCommit)

        assertThat(recovered).isEqualTo(legitimateCommit)
    }

    @Test
    fun prefixAtKnownTruncationLimit_shouldRestoreCachedText() {
        val cached = "x".repeat(700)
        val truncated = cached.take(512)
        EditorClipboardBridge.rememberCopiedText(cached)

        assertThat(EditorClipboardBridge.recoverPossiblyTruncatedClipboardText(truncated))
            .isEqualTo(cached)
        assertThat(EditorClipboardBridge.recoverPossiblyTruncatedImeCommitText(truncated))
            .isEqualTo(cached)
    }

    @Test
    fun longLegitimatePrefixAwayFromTruncationLimit_shouldRemainUnchanged() {
        val cached = "x".repeat(900)
        val legitimateCommit = cached.take(600)
        EditorClipboardBridge.rememberCopiedText(cached)

        assertThat(EditorClipboardBridge.recoverPossiblyTruncatedClipboardText(legitimateCommit))
            .isEqualTo(legitimateCommit)
        assertThat(EditorClipboardBridge.recoverPossiblyTruncatedImeCommitText(legitimateCommit))
            .isEqualTo(legitimateCommit)
    }

    @Test
    fun copyCacheAge_shouldRejectClockRollbackAndExpiredSnapshot() {
        assertThat(EditorClipboardBridge.isRecentCopy(copiedAtUptimeMs = 10L, nowUptimeMs = 9L)).isFalse()
        assertThat(EditorClipboardBridge.isRecentCopy(copiedAtUptimeMs = 10L, nowUptimeMs = 120_010L)).isTrue()
        assertThat(EditorClipboardBridge.isRecentCopy(copiedAtUptimeMs = 10L, nowUptimeMs = 120_011L)).isFalse()
    }
}
