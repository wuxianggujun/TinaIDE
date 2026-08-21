package com.wuxianggujun.tinaide.ui.compose.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DiagnosticQuickFixAvailabilityTest {

    @Test
    fun `action is shown only when an enabled quick fix is available`() {
        assertThat(DiagnosticQuickFixAvailability.CHECKING.shouldShowAction).isFalse()
        assertThat(DiagnosticQuickFixAvailability.UNAVAILABLE.shouldShowAction).isFalse()
        assertThat(DiagnosticQuickFixAvailability.AVAILABLE.shouldShowAction).isTrue()
    }
}
