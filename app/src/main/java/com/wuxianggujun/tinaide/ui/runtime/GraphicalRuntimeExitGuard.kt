package com.wuxianggujun.tinaide.ui.runtime

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A shutdown deadline, not a runtime limit. Nothing is scheduled while the game is running. */
internal class GraphicalRuntimeExitGuard(
    private val terminateProcess: () -> Unit,
    private val gracePeriodMillis: Long = EXIT_GRACE_PERIOD_MS,
    private val schedule: (Long, () -> Unit) -> Unit = ::scheduleOnWorker,
) {
    private val exitRequested = AtomicBoolean(false)
    private val terminated = AtomicBoolean(false)

    fun requestExit() {
        if (exitRequested.compareAndSet(false, true)) {
            schedule(gracePeriodMillis, ::terminateOnce)
        }
    }

    fun onDestroyed() {
        if (exitRequested.get()) terminateOnce()
    }

    private fun terminateOnce() {
        if (terminated.compareAndSet(false, true)) terminateProcess()
    }

    companion object {
        const val EXIT_GRACE_PERIOD_MS = 2_000L

        private fun scheduleOnWorker(delayMillis: Long, action: () -> Unit) {
            // NativeActivity.onDestroy can block the main Looper waiting for android_main.
            // A main Handler/lifecycleScope timeout would never get a chance to run there.
            val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "graphical-runtime-exit").apply { isDaemon = true }
            }
            executor.schedule({ action() }, delayMillis, TimeUnit.MILLISECONDS)
            executor.shutdown()
        }
    }
}
