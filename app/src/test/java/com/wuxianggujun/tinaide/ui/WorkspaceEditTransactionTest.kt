package com.wuxianggujun.tinaide.ui

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class WorkspaceEditTransactionTest {
    @Test
    fun cancellationAfterWriteBeforeApplyReturnsRestoresEveryCompletedStep() = runTest {
        val contents = mutableListOf("first", "second")
        val written = CompletableDeferred<Unit>()
        val job = launch {
            WorkspaceEditTransaction.execute(listOf(
                WorkspaceEditTransaction.Step(
                    apply = { mark -> contents[0] = "changed first"; mark(); true },
                    rollback = { contents[0] = "first"; true },
                ),
                WorkspaceEditTransaction.Step(
                    apply = { mark -> contents[1] = "changed second"; mark(); written.complete(Unit); awaitCancellation() },
                    rollback = { contents[1] = "second"; true },
                ),
            ))
        }
        written.await()
        job.cancelAndJoin()
        assertThat(contents).containsExactly("first", "second").inOrder()
    }

    @Test
    fun rejectedStepIsNotRolledBackAndOtherRollbackFailuresAreReported() = runTest {
        var rejectedRollbackCalled = false
        var failures = 0
        val result = WorkspaceEditTransaction.execute(listOf(
            WorkspaceEditTransaction.Step(apply = { mark -> mark(); true }, rollback = { false }),
            WorkspaceEditTransaction.Step(apply = { false }, rollback = { rejectedRollbackCalled = true; true }),
        ), onRollbackFailure = { failures = it })
        assertThat(result).isFalse()
        assertThat(rejectedRollbackCalled).isFalse()
        assertThat(failures).isEqualTo(1)
    }

    @Test
    fun successfulEditDoesNotRollback() = runTest {
        var rolledBack = false
        val result = WorkspaceEditTransaction.execute(listOf(
            WorkspaceEditTransaction.Step(apply = { mark -> mark(); true }, rollback = { rolledBack = true; true }),
        ))
        assertThat(result).isTrue()
        assertThat(rolledBack).isFalse()
    }
}
