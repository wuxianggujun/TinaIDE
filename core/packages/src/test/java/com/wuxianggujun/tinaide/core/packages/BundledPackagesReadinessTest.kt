package com.wuxianggujun.tinaide.core.packages

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test

class BundledPackagesReadinessTest {
    @Test
    fun `waiter resumes when current install becomes ready`() = runTest {
        BundledPackagesReadiness.markInstalling()
        try {
            val result = async {
                BundledPackagesReadiness.awaitCurrentInstall(timeoutMillis = 1_000)
            }
            yield()
            BundledPackagesReadiness.markReady()

            assertThat(result.await()).isEqualTo(BundledPackagesReadiness.State.READY)
        } finally {
            BundledPackagesReadiness.markReady()
        }
    }

    @Test
    fun `waiter times out instead of blocking compilation forever`() = runTest {
        BundledPackagesReadiness.markInstalling()
        try {
            assertThat(BundledPackagesReadiness.awaitCurrentInstall(timeoutMillis = 1))
                .isEqualTo(BundledPackagesReadiness.State.TIMED_OUT)
        } finally {
            BundledPackagesReadiness.markReady()
        }
    }
}
