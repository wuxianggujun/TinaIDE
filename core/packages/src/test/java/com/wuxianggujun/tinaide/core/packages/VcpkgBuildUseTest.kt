package com.wuxianggujun.tinaide.core.packages

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VcpkgBuildUseTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun nestedAndChildCoroutinesShareOnePinnedRootButDisabledUsesDoNot() = runTest {
        val key = File(temporary.root, ".current")
        val first = temporary.newFolder()
        val second = temporary.newFolder()
        var current = first
        VcpkgBuildUse.during(true) {
            assertThat(VcpkgBuildUse.sharedRoot(key) { current }).isEqualTo(first)
            current = second
            VcpkgBuildUse.during(true) {
                withContext(Dispatchers.Default) {
                    assertThat(VcpkgBuildUse.sharedRoot(key) { current }).isEqualTo(first)
                }
            }
        }
        VcpkgBuildUse.during(false) {
            assertThat(VcpkgBuildUse.sharedRoot(key) { current }).isEqualTo(second)
            current = first
            assertThat(VcpkgBuildUse.sharedRoot(key) { current }).isEqualTo(first)
        }
    }

    @Test fun collectionWaitsForEveryBuildAndRecomputesReferencesAtRetirement() = runTest {
        val store = temporary.newFolder()
        val obsolete = File(store, "obsolete").apply { mkdirs() }
        val newlyReferenced = File(store, "new-current").apply { mkdirs() }
        val started = List(2) { CompletableDeferred<Unit>() }
        val finish = List(2) { CompletableDeferred<Unit>() }
        val builds = List(2) { index ->
            async {
                VcpkgBuildUse.during(true) {
                    started[index].complete(Unit)
                    finish[index].await()
                }
            }
        }
        started.forEach { it.await() }
        var refreshed = false
        VcpkgBuildUse.retireWhenIdle(store, listOf(obsolete, newlyReferenced)) {
            refreshed = true
            VcpkgBuildUse.retireWhenIdle(store, listOf(obsolete)) { error("No build is active") }
        }
        finish[0].complete(Unit)
        builds[0].await()
        assertThat(obsolete.isDirectory).isTrue()
        assertThat(refreshed).isFalse()
        finish[1].complete(Unit)
        builds[1].await()
        assertThat(refreshed).isTrue()
        assertThat(obsolete.exists()).isFalse()
        assertThat(newlyReferenced.isDirectory).isTrue()
    }

    @Test fun cancelledBuildStillReleasesItsLeaseAndRunsDeferredCollection() = runTest {
        val store = temporary.newFolder()
        val obsolete = File(store, "obsolete").apply { mkdirs() }
        val started = CompletableDeferred<Unit>()
        val build = async {
            VcpkgBuildUse.during(true) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        VcpkgBuildUse.retireWhenIdle(store, listOf(obsolete)) {
            VcpkgBuildUse.retireWhenIdle(store, listOf(obsolete)) { error("Lease was not released") }
        }
        build.cancelAndJoin()
        assertThat(obsolete.exists()).isFalse()
    }

    @Test fun cleanupFailureDoesNotChangeTheCompletedOperationResult() = runTest {
        val store = temporary.newFolder()
        val obsolete = File(store, "obsolete").apply { mkdirs() }
        val result = VcpkgBuildUse.during(true) {
            VcpkgBuildUse.retireWhenIdle(store, listOf(obsolete)) { throw IOException("fixture cleanup failure") }
            42
        }
        assertThat(result).isEqualTo(42)
        assertThat(obsolete.isDirectory).isTrue()
    }
}
