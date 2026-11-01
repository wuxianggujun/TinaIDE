package com.wuxianggujun.tinaide.ui.runtime

import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/** Acknowledges process/Activity entry, not engine initialization or a first frame. */
internal class GraphicalRuntimeLaunchSignal {
    private val started = CompletableDeferred<Unit>()
    private val pending = AtomicBoolean(true)
    private val ticket = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != CLAIM) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(DESCRIPTOR)
            val accepted = getCallingUid() == Process.myUid() && pending.compareAndSet(true, false)
            if (accepted) started.complete(Unit)
            reply?.writeNoException()
            reply?.writeInt(if (accepted) 1 else 0)
            return true
        }
    }

    var wasLaunched = false
        private set

    fun attachTo(intent: Intent) {
        check(!wasLaunched) { "A graphical launch ticket can only be attached once" }
        intent.putExtra(EXTRA_LAUNCH, Bundle().apply { putBinder(TICKET, ticket) })
        wasLaunched = true
    }

    suspend fun awaitStarted(): Boolean = try {
        withTimeoutOrNull(START_WAIT_MS) {
            started.await()
            true
        } ?: false
    } finally {
        cancelPending()
    }

    fun cancelPending() {
        pending.set(false)
    }

    companion object {
        private const val EXTRA_LAUNCH = "graphical_runtime_launch"
        private const val TICKET = "ticket"
        private const val DESCRIPTOR = "com.wuxianggujun.tinaide.GraphicalRuntimeLaunch"
        private const val CLAIM = IBinder.FIRST_CALL_TRANSACTION
        private const val START_WAIT_MS = 10_000L

        /** Synchronous, one-shot claim before touching any engine/global state. */
        fun claim(intent: Intent): Boolean {
            val ticket = intent.getBundleExtra(EXTRA_LAUNCH)?.getBinder(TICKET) ?: return false
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeInterfaceToken(DESCRIPTOR)
                if (!ticket.transact(CLAIM, data, reply, 0)) return false
                reply.readException()
                reply.readInt() == 1
            } catch (error: Exception) {
                Timber.tag("GraphicalRuntimeLaunch").w(error, "Launch ticket is no longer available")
                false
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }
}
