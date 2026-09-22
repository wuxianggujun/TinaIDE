package com.wuxianggujun.tinaide.core.editorlsp

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LspAttachmentReuseSupportTest {

    @Test
    fun shouldReuseExistingAttachment_whenConnectedToTheSameFile() {
        assertThat(
            LspAttachmentReuseSupport.shouldReuseExistingAttachment(
                sessionConnected = true,
                sessionFilePath = "/data/projects/TabTest/src/main.cpp",
                requestedFilePath = "/data/projects/TabTest/src/main.cpp",
            )
        ).isTrue()
    }

    @Test
    fun shouldReuseExistingAttachment_shouldNormalizeSeparators() {
        assertThat(
            LspAttachmentReuseSupport.shouldReuseExistingAttachment(
                sessionConnected = true,
                sessionFilePath = """C:\work\main.cpp""",
                requestedFilePath = "C:/work/main.cpp",
            )
        ).isTrue()
    }

    @Test
    fun shouldReuseExistingAttachment_shouldRejectDisconnectedOrDifferentFile() {
        assertThat(
            LspAttachmentReuseSupport.shouldReuseExistingAttachment(
                sessionConnected = false,
                sessionFilePath = "/data/projects/TabTest/src/main.cpp",
                requestedFilePath = "/data/projects/TabTest/src/main.cpp",
            )
        ).isFalse()
        assertThat(
            LspAttachmentReuseSupport.shouldReuseExistingAttachment(
                sessionConnected = true,
                sessionFilePath = "/data/projects/TabTest/include/vector",
                requestedFilePath = "/data/projects/TabTest/src/main.cpp",
            )
        ).isFalse()
        assertThat(
            LspAttachmentReuseSupport.shouldReuseExistingAttachment(
                sessionConnected = true,
                sessionFilePath = null,
                requestedFilePath = "/data/projects/TabTest/src/main.cpp",
            )
        ).isFalse()
    }

    @Test
    fun shouldAnnounceConnecting_onlyWhenSharedClangdIsDown() {
        assertThat(LspAttachmentReuseSupport.shouldAnnounceConnecting(sharedSessionConnected = false)).isTrue()
        assertThat(LspAttachmentReuseSupport.shouldAnnounceConnecting(sharedSessionConnected = true)).isFalse()
    }

    @Test
    fun shouldReleaseTabSessionBeforeSharedAttach_onlyWhenSharedClangdIsDown() {
        assertThat(
            LspAttachmentReuseSupport.shouldReleaseTabSessionBeforeSharedAttach(sharedSessionConnected = false)
        ).isTrue()
        assertThat(
            LspAttachmentReuseSupport.shouldReleaseTabSessionBeforeSharedAttach(sharedSessionConnected = true)
        ).isFalse()
    }

    @Test
    fun canActivateExistingSharedCxx_whenLiveSessionMatchesWorkspace() {
        assertThat(
            LspAttachmentReuseSupport.canActivateExistingSharedCxx(
                sharedSessionConnected = true,
                usingRemoteLsp = false,
                wantRemote = false,
                sharedWorkspaceRoot = "/data/projects/TabTest",
                requestedWorkspaceRoot = "/data/projects/TabTest",
            )
        ).isTrue()
        assertThat(
            LspAttachmentReuseSupport.canActivateExistingSharedCxx(
                sharedSessionConnected = true,
                usingRemoteLsp = false,
                wantRemote = false,
                sharedWorkspaceRoot = """C:\work\TabTest""",
                requestedWorkspaceRoot = "C:/work/TabTest",
            )
        ).isTrue()
    }

    @Test
    fun canActivateExistingSharedCxx_shouldRejectDeadOrMismatchedSession() {
        assertThat(
            LspAttachmentReuseSupport.canActivateExistingSharedCxx(
                sharedSessionConnected = false,
                usingRemoteLsp = false,
                wantRemote = false,
                sharedWorkspaceRoot = "/data/projects/TabTest",
                requestedWorkspaceRoot = "/data/projects/TabTest",
            )
        ).isFalse()
        assertThat(
            LspAttachmentReuseSupport.canActivateExistingSharedCxx(
                sharedSessionConnected = true,
                usingRemoteLsp = true,
                wantRemote = false,
                sharedWorkspaceRoot = "/data/projects/TabTest",
                requestedWorkspaceRoot = "/data/projects/TabTest",
            )
        ).isFalse()
        assertThat(
            LspAttachmentReuseSupport.canActivateExistingSharedCxx(
                sharedSessionConnected = true,
                usingRemoteLsp = false,
                wantRemote = false,
                sharedWorkspaceRoot = "/data/projects/TabTest",
                requestedWorkspaceRoot = "/data/projects/Other",
            )
        ).isFalse()
        assertThat(
            LspAttachmentReuseSupport.canActivateExistingSharedCxx(
                sharedSessionConnected = true,
                usingRemoteLsp = false,
                wantRemote = false,
                sharedWorkspaceRoot = null,
                requestedWorkspaceRoot = "/data/projects/TabTest",
            )
        ).isFalse()
    }

    @Test
    fun needsDocumentActivation_whenLiveCxxSessionIsNotCurrentDocument() {
        assertThat(
            LspAttachmentReuseSupport.needsDocumentActivation(
                sessionKindIsCxx = true,
                sessionConnected = true,
                isCurrentDocument = false,
            )
        ).isTrue()
        assertThat(
            LspAttachmentReuseSupport.needsDocumentActivation(
                sessionKindIsCxx = true,
                sessionConnected = true,
                isCurrentDocument = true,
            )
        ).isFalse()
        assertThat(
            LspAttachmentReuseSupport.needsDocumentActivation(
                sessionKindIsCxx = true,
                sessionConnected = false,
                isCurrentDocument = false,
            )
        ).isFalse()
        assertThat(
            LspAttachmentReuseSupport.needsDocumentActivation(
                sessionKindIsCxx = false,
                sessionConnected = true,
                isCurrentDocument = false,
            )
        ).isFalse()
    }
}
