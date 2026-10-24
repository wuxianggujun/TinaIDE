package com.wuxianggujun.tinaide.core.security

import com.google.common.truth.Truth.assertThat
import javax.crypto.AEADBadTagException
import org.junit.Test

class EncryptedPreferencesRecoveryTest {

    @Test
    fun recover_shouldUseFirstSuccessfulOpenWithoutResetting() {
        var attempts = 0
        var clearCount = 0
        var masterKeyResetCount = 0

        val result = recoverEncryptedPreferences(
            open = {
                attempts++
                "preferences"
            },
            onFailure = { _, _ -> },
            resetPreferences = { clearCount++ },
            resetMasterKey = { masterKeyResetCount++ },
        )

        assertThat(result).isEqualTo("preferences")
        assertThat(attempts).isEqualTo(1)
        assertThat(clearCount).isEqualTo(0)
        assertThat(masterKeyResetCount).isEqualTo(0)
    }

    @Test
    fun recover_shouldClearPreferencesBeforeRetrying() {
        var attempts = 0
        var clearCount = 0
        var masterKeyResetCount = 0

        val result = recoverEncryptedPreferences(
            open = {
                attempts++
                if (attempts == 1) throw AEADBadTagException("corrupted keyset")
                "preferences"
            },
            onFailure = { _, _ -> },
            resetPreferences = { clearCount++ },
            resetMasterKey = { masterKeyResetCount++ },
        )

        assertThat(result).isEqualTo("preferences")
        assertThat(attempts).isEqualTo(2)
        assertThat(clearCount).isEqualTo(1)
        assertThat(masterKeyResetCount).isEqualTo(0)
    }

    @Test
    fun recover_shouldResetMasterKeyWhenPreferencesResetIsNotEnough() {
        var attempts = 0
        var clearCount = 0
        var masterKeyResetCount = 0

        val result = recoverEncryptedPreferences(
            open = {
                attempts++
                if (attempts <= 2) throw AEADBadTagException("invalidated master key")
                "preferences"
            },
            onFailure = { _, _ -> },
            resetPreferences = { clearCount++ },
            resetMasterKey = { masterKeyResetCount++ },
        )

        assertThat(result).isEqualTo("preferences")
        assertThat(attempts).isEqualTo(3)
        assertThat(clearCount).isEqualTo(1)
        assertThat(masterKeyResetCount).isEqualTo(1)
    }

    @Test
    fun recover_shouldReturnNullAndReportAllAttemptsWhenRecoveryFails() {
        var attempts = 0
        var clearCount = 0
        var masterKeyResetCount = 0
        val failedAttempts = mutableListOf<Int>()

        val result = recoverEncryptedPreferences<String>(
            open = {
                attempts++
                throw AEADBadTagException("unrecoverable keystore")
            },
            onFailure = { attempt, _ -> failedAttempts += attempt },
            resetPreferences = { clearCount++ },
            resetMasterKey = { masterKeyResetCount++ },
        )

        assertThat(result).isNull()
        assertThat(attempts).isEqualTo(3)
        assertThat(clearCount).isEqualTo(1)
        assertThat(masterKeyResetCount).isEqualTo(1)
        assertThat(failedAttempts).containsExactly(1, 2, 3).inOrder()
    }
}
