package com.wuxianggujun.tinaide.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One user action owns saving, compilation and the queued launch until it is consumed. */
class CompileActionGate {
    private val mutableBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = mutableBusy.asStateFlow()

    private var current: Ticket? = null
    private var nextId = 0L

    @Synchronized
    fun tryBegin(): Ticket? {
        if (current != null) return null
        return Ticket(this, ++nextId).also {
            current = it
            mutableBusy.value = true
        }
    }

    @Synchronized
    fun cancel() {
        val ticket = current ?: return
        ticket.cancelled.complete(Unit)
        // A launch must revoke its Binder ticket before a successor can touch staging.
        if (ticket.phase != Phase.LAUNCHING) finish(ticket)
    }

    @Synchronized
    private fun isCurrent(ticket: Ticket): Boolean = current === ticket && !ticket.cancelled.isCompleted

    @Synchronized
    private fun advance(ticket: Ticket, from: Phase, to: Phase): Boolean {
        if (!isCurrent(ticket) || ticket.phase != from) return false
        ticket.phase = to
        return true
    }

    @Synchronized
    private fun finish(ticket: Ticket) {
        // A cancelled action's finally block must never release its successor.
        if (current !== ticket) return
        current = null
        mutableBusy.value = false
    }

    internal enum class Phase { PREPARING, COMPILING, RESULT, LAUNCHING }

    class Ticket internal constructor(private val owner: CompileActionGate, val id: Long) {
        internal var phase = Phase.PREPARING
        internal val cancelled = CompletableDeferred<Unit>()

        val isCurrent: Boolean get() = owner.isCurrent(this)

        fun startCompilation(): Boolean = owner.advance(this, Phase.PREPARING, Phase.COMPILING)

        fun claimResult(): Boolean = owner.advance(this, Phase.COMPILING, Phase.RESULT)

        fun claimLaunch(): Boolean = owner.advance(this, Phase.RESULT, Phase.LAUNCHING)

        fun finish() = owner.finish(this)
    }
}
