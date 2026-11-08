package com.wuxianggujun.tinaide.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Records each write before a dispatcher handoff can discard its result on cancellation. */
internal object WorkspaceEditTransaction {
    class Step(val apply: suspend (markApplied: () -> Unit) -> Boolean, val rollback: suspend () -> Boolean)

    suspend fun execute(steps: List<Step>, onRollbackFailure: (Int) -> Unit = {}): Boolean {
        val applied = mutableListOf<Step>()
        var committed = false
        try {
            for (step in steps) {
                currentCoroutineContext().ensureActive()
                if (!step.apply { applied += step }) return false
            }
            currentCoroutineContext().ensureActive()
            committed = true
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.tag("WorkspaceEdit").w(error, "Workspace edit failed; restoring completed changes")
            return false
        } finally {
            if (!committed) withContext(NonCancellable) {
                var failures = 0
                applied.asReversed().forEach { step ->
                    try {
                        if (!step.rollback()) failures++
                    } catch (error: Exception) {
                        failures++
                        Timber.tag("WorkspaceEdit").e(error, "Workspace edit rollback failed")
                    }
                }
                if (failures > 0) onRollbackFailure(failures)
            }
        }
    }
}
