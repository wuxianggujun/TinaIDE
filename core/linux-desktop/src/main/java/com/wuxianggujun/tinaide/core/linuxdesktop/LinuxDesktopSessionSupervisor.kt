package com.wuxianggujun.tinaide.core.linuxdesktop

import java.util.concurrent.atomic.AtomicReference

/** Bounded restart policy for a desktop process that exits unexpectedly. */
data class LinuxDesktopRestartPolicy(
    val maxRestarts: Int = 2,
    val restartDelayMs: Long = 1_000L,
) {
    init {
        require(maxRestarts >= 0) { "Desktop restart count must not be negative" }
        require(restartDelayMs >= 0L) { "Desktop restart delay must not be negative" }
    }
}

enum class LinuxDesktopSupervisorPhase {
    IDLE,
    STARTING,
    RUNNING,
    RESTARTING,
    STOPPED,
    FAILED,
}

data class LinuxDesktopSupervisorStatus(
    val phase: LinuxDesktopSupervisorPhase,
    val restartAttempt: Int = 0,
    val exitCode: Int? = null,
    val failure: Throwable? = null,
)

/**
 * Owns one desktop session and restarts it after unexpected exits.
 *
 * The restart budget is cumulative for this supervisor instance. Calling
 * [stop] is terminal and never consumes the restart budget.
 */
class LinuxDesktopSessionSupervisor(
    private val launchSession: () -> Result<LinuxDesktopSession>,
    private val restartPolicy: LinuxDesktopRestartPolicy = LinuxDesktopRestartPolicy(),
    private val restartWaiter: (Long) -> Unit = { delayMs -> Thread.sleep(delayMs) },
    private val workerFactory: (Runnable) -> Thread = { runnable ->
        Thread(runnable, "tina-linux-desktop-watchdog").apply { isDaemon = true }
    },
) : AutoCloseable {
    private val lock = Any()
    private val statusRef = AtomicReference(
        LinuxDesktopSupervisorStatus(LinuxDesktopSupervisorPhase.IDLE)
    )

    private var started = false
    private var stopRequested = false
    private var currentSession: LinuxDesktopSession? = null
    private var worker: Thread? = null
    private var statusListener: (LinuxDesktopSupervisorStatus) -> Unit = {}

    val status: LinuxDesktopSupervisorStatus
        get() = statusRef.get()

    fun start(
        onStatusChanged: (LinuxDesktopSupervisorStatus) -> Unit = {},
    ): Result<Unit> {
        synchronized(lock) {
            check(!started) { "Desktop session supervisor can only be started once" }
            started = true
            statusListener = onStatusChanged
        }

        publish(LinuxDesktopSupervisorStatus(LinuxDesktopSupervisorPhase.STARTING))
        val initialSession = launchSessionSafely().getOrElse { error ->
            publish(
                LinuxDesktopSupervisorStatus(
                    phase = LinuxDesktopSupervisorPhase.FAILED,
                    failure = error,
                )
            )
            return Result.failure(error)
        }

        synchronized(lock) {
            if (stopRequested) {
                initialSession.stop()
                publish(LinuxDesktopSupervisorStatus(LinuxDesktopSupervisorPhase.STOPPED))
                return Result.success(Unit)
            }
            currentSession = initialSession
        }
        publish(LinuxDesktopSupervisorStatus(LinuxDesktopSupervisorPhase.RUNNING))

        val monitor = workerFactory(Runnable { monitor(initialSession) })
        synchronized(lock) {
            worker = monitor
        }
        monitor.start()
        return Result.success(Unit)
    }

    fun stop() {
        val sessionToStop: LinuxDesktopSession?
        val workerToInterrupt: Thread?
        synchronized(lock) {
            if (stopRequested) return
            stopRequested = true
            sessionToStop = currentSession
            currentSession = null
            workerToInterrupt = worker
            worker = null
        }

        sessionToStop?.stop()
        workerToInterrupt?.interrupt()
        publish(LinuxDesktopSupervisorStatus(LinuxDesktopSupervisorPhase.STOPPED))
    }

    override fun close() = stop()

    private fun monitor(initialSession: LinuxDesktopSession) {
        var session = initialSession
        var restartAttempt = 0
        var lastExitCode: Int? = null
        var lastFailure: Throwable? = null

        while (!isStopRequested()) {
            val outcome = waitForExit(session)
            if (isStopRequested()) return

            synchronized(lock) {
                if (currentSession === session) currentSession = null
            }
            lastExitCode = outcome.exitCode
            lastFailure = outcome.failure

            while (!isStopRequested()) {
                if (restartAttempt >= restartPolicy.maxRestarts) {
                    publish(
                        LinuxDesktopSupervisorStatus(
                            phase = LinuxDesktopSupervisorPhase.FAILED,
                            restartAttempt = restartAttempt,
                            exitCode = lastExitCode,
                            failure = lastFailure,
                        )
                    )
                    clearWorker()
                    return
                }

                restartAttempt += 1
                publish(
                    LinuxDesktopSupervisorStatus(
                        phase = LinuxDesktopSupervisorPhase.RESTARTING,
                        restartAttempt = restartAttempt,
                        exitCode = lastExitCode,
                        failure = lastFailure,
                    )
                )
                if (!awaitRestartDelay()) return

                publish(
                    LinuxDesktopSupervisorStatus(
                        phase = LinuxDesktopSupervisorPhase.STARTING,
                        restartAttempt = restartAttempt,
                    )
                )
                val restarted = launchSessionSafely()
                val nextSession = restarted.getOrNull()
                if (nextSession == null) {
                    lastExitCode = null
                    lastFailure = restarted.exceptionOrNull()
                    continue
                }

                synchronized(lock) {
                    if (stopRequested) {
                        nextSession.stop()
                        return
                    }
                    currentSession = nextSession
                }
                session = nextSession
                publish(
                    LinuxDesktopSupervisorStatus(
                        phase = LinuxDesktopSupervisorPhase.RUNNING,
                        restartAttempt = restartAttempt,
                    )
                )
                break
            }
        }
    }

    private fun launchSessionSafely(): Result<LinuxDesktopSession> = try {
        launchSession()
    } catch (error: Throwable) {
        Result.failure(error)
    }

    private fun waitForExit(session: LinuxDesktopSession): ExitOutcome = try {
        ExitOutcome(exitCode = session.waitFor())
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        ExitOutcome(failure = interrupted)
    } catch (error: Throwable) {
        ExitOutcome(failure = error)
    }

    private fun awaitRestartDelay(): Boolean {
        if (restartPolicy.restartDelayMs == 0L) return !isStopRequested()
        return try {
            restartWaiter(restartPolicy.restartDelayMs)
            !isStopRequested()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun isStopRequested(): Boolean = synchronized(lock) { stopRequested }

    private fun clearWorker() {
        synchronized(lock) {
            worker = null
            currentSession = null
        }
    }

    private fun publish(nextStatus: LinuxDesktopSupervisorStatus) {
        val previous = statusRef.getAndSet(nextStatus)
        if (previous == nextStatus) return
        runCatching { statusListener(nextStatus) }
    }

    private data class ExitOutcome(
        val exitCode: Int? = null,
        val failure: Throwable? = null,
    )
}
