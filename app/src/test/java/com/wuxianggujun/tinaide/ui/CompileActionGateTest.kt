package com.wuxianggujun.tinaide.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CompileActionGateTest {
    @Test
    fun `one ticket owns preparation compilation and the pending launch`() {
        val gate = CompileActionGate()
        val ticket = checkNotNull(gate.tryBegin())
        repeat(20) { assertThat(gate.tryBegin()).isNull() }
        assertThat(ticket.startCompilation()).isTrue()
        assertThat(ticket.startCompilation()).isFalse()
        assertThat(ticket.claimLaunch()).isFalse()
        assertThat(ticket.claimResult()).isTrue()
        assertThat(ticket.claimResult()).isFalse()
        assertThat(gate.tryBegin()).isNull()
        assertThat(ticket.claimLaunch()).isTrue()
        assertThat(ticket.claimLaunch()).isFalse()
        ticket.finish()
        assertThat(gate.isBusy.value).isFalse()
        assertThat(gate.tryBegin()).isNotNull()
    }

    @Test
    fun `old cancellation and completion cannot release a successor`() {
        val gate = CompileActionGate()
        val old = checkNotNull(gate.tryBegin())
        check(old.startCompilation())
        gate.cancel()
        val next = checkNotNull(gate.tryBegin())
        assertThat(old.claimResult()).isFalse()
        old.finish()
        assertThat(next.isCurrent).isTrue()
        assertThat(gate.isBusy.value).isTrue()
        assertThat(gate.tryBegin()).isNull()
    }

    @Test
    fun `launch cancellation retains ownership until launch cleanup finishes`() {
        val gate = CompileActionGate()
        val ticket = checkNotNull(gate.tryBegin())
        check(ticket.startCompilation())
        check(ticket.claimResult())
        check(ticket.claimLaunch())
        gate.cancel()
        assertThat(ticket.isCurrent).isFalse()
        assertThat(ticket.cancelled.isCompleted).isTrue()
        assertThat(gate.tryBegin()).isNull()
        ticket.finish()
        assertThat(gate.tryBegin()).isNotNull()
    }
}
