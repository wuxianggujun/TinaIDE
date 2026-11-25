package com.wuxianggujun.tinaide.ui.runtime

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Process
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/** Keeps all graphical engines' dlopen/global state confined to one process per explicit run. */
internal class GraphicalRuntimeProcessController(
    private val packageName: String,
    private val ownerUid: Int,
    private val callerPid: Int,
    private val runningProcesses: () -> List<ProcessInfo>,
    private val killProcess: (Int) -> Unit,
) {
    internal data class ProcessInfo(val pid: Int, val uid: Int, val name: String)

    /** Called only by an explicit new run, before overwriting any staged .so files. */
    suspend fun stopPrevious(): Boolean = try {
        val previous = ownedRuntimeProcesses()
        previous.forEach { process ->
            Timber.tag(TAG).i("Stopping previous graphical runtime process pid=%d", process.pid)
            killProcess(process.pid)
        }
        withTimeoutOrNull(RESTART_WAIT_MS) {
            // Do not repeatedly kill: a new PID may belong to a later session.
            while (ownedRuntimeProcesses().isNotEmpty()) delay(PROCESS_POLL_MS)
            true
        } ?: false
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        Timber.tag(TAG).e(error, "Unable to confirm previous graphical runtime process exit")
        false
    }

    private fun ownedRuntimeProcesses(): List<ProcessInfo> = runningProcesses().filter {
        it.pid != callerPid && isOwnedRuntime(it, packageName, ownerUid)
    }

    companion object {
        private const val TAG = "GraphicalRuntimeProcess"
        // Explicit allowlist: never include IDE, plugin, crash or X11 processes.
        internal val PROCESS_SUFFIXES = setOf(":gui", ":sdl", ":sdl2")
        private const val RESTART_WAIT_MS = 2_000L
        private const val PROCESS_POLL_MS = 50L

        fun forContext(context: Context): GraphicalRuntimeProcessController {
            val manager = context.getSystemService(ActivityManager::class.java)
            return GraphicalRuntimeProcessController(
                packageName = context.packageName,
                ownerUid = context.applicationInfo.uid,
                callerPid = Process.myPid(),
                runningProcesses = {
                    // A missing snapshot is not evidence that the old process is gone.
                    checkNotNull(manager?.runningAppProcesses) { "No running-process snapshot" }
                        .map { ProcessInfo(it.pid, it.uid, it.processName.orEmpty()) }
                },
                killProcess = Process::killProcess,
            )
        }

        fun terminateCurrent(context: Context) {
            val current = ProcessInfo(Process.myPid(), Process.myUid(), Application.getProcessName())
            if (!isOwnedRuntime(current, context.packageName, context.applicationInfo.uid)) {
                Timber.tag(TAG).e("Refusing to terminate non-graphical runtime process pid=%d", current.pid)
                return
            }
            Process.killProcess(current.pid)
        }

        internal fun isOwnedRuntime(process: ProcessInfo, packageName: String, ownerUid: Int): Boolean =
            process.pid > 0 && process.uid == ownerUid &&
                PROCESS_SUFFIXES.any { suffix -> process.name == packageName + suffix }
    }
}
