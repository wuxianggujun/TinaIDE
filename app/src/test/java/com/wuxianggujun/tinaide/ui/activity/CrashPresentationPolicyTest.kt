package com.wuxianggujun.tinaide.ui.activity

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CrashPresentationPolicyTest {
    private val packageName = "com.example.ide"

    @Test
    fun everyUserRuntime_lacksHostActionsEvenWithTheWrongLegacySourceTag() {
        for (suffix in listOf(":gui", ":sdl", ":sdl2")) {
            val origin = resolveCrashScreenOrigin(packageName, packageName + suffix, CrashActivity.SOURCE_APP, "")
            assertThat(origin).isEqualTo(CrashScreenOrigin.USER_PROGRAM)
            assertThat(origin.canRestartIde).isFalse()
            assertThat(origin.canUploadToHost).isFalse()
        }
    }

    @Test
    fun hostIdentity_doesNotBecomeAUserProgramBecauseOfAPathMentionInItsLog() {
        val origin = resolveCrashScreenOrigin(packageName, packageName, null, "/files/run-bin/main")
        assertThat(origin).isEqualTo(CrashScreenOrigin.HOST_APP)
        assertThat(origin.canRestartIde).isTrue()
    }

    @Test
    fun unknownProcess_neverDefaultsToRestartingOrUploadingAsTheHost() {
        val origin = resolveCrashScreenOrigin(packageName, "$packageName:x11", CrashActivity.SOURCE_APP, "")
        assertThat(origin).isEqualTo(CrashScreenOrigin.COMPONENT)
        assertThat(origin.canRestartIde).isFalse()
        assertThat(origin.canUploadToHost).isFalse()
        assertThat(resolveCrashScreenOrigin(packageName, "", null, ""))
            .isEqualTo(CrashScreenOrigin.COMPONENT)
    }

    @Test
    fun missingProcessIdentity_doesNotGrantHostActionsFromTheLegacyAppTag() {
        for (processName in listOf("", "   ")) {
            val origin = resolveCrashScreenOrigin(packageName, processName, CrashActivity.SOURCE_APP, "")
            assertThat(origin).isEqualTo(CrashScreenOrigin.COMPONENT)
            assertThat(origin.canRestartIde).isFalse()
            assertThat(origin.canUploadToHost).isFalse()
        }
    }

    @Test
    fun legacyTombstone_recoversUserIdentityBeforeApplyingTheAppDefault() {
        val origin = resolveCrashScreenOrigin(
            packageName, "", CrashActivity.SOURCE_APP, ">>> $packageName:sdl <<<"
        )
        assertThat(origin).isEqualTo(CrashScreenOrigin.USER_PROGRAM)
    }

    @Test
    fun previewSummary_isACompactIdentifierNotAnEntireDiagnosticParagraph() {
        assertThat(crashSummaryIdentifier("signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0"))
            .isEqualTo("SIGSEGV")
        assertThat(crashSummaryIdentifier("java.lang.IllegalStateException: a very long message\nstack"))
            .isEqualTo("IllegalStateException")
        assertThat(crashSummaryIdentifier("Device metadata and no supported failure identifier")).isNull()
    }
}
