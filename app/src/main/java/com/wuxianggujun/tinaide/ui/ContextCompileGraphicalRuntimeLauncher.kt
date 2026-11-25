package com.wuxianggujun.tinaide.ui

import android.content.Context
import android.content.Intent
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.ui.runtime.GraphicalRuntimeLaunchRequest
import com.wuxianggujun.tinaide.ui.runtime.GraphicalRuntimeLaunchSignal
import com.wuxianggujun.tinaide.ui.runtime.GraphicalRuntimeProcessController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

internal class ContextCompileGraphicalRuntimeLauncher(
    private val context: Context,
    private val onError: (String) -> Unit,
    private val activityStarter: (Intent) -> Unit = context::startActivity,
) : CompileUiEventObserver.GraphicalRuntimeLauncher {
    private val processes = GraphicalRuntimeProcessController.forContext(context.applicationContext)

    override suspend fun open(request: GraphicalRuntimeLaunchRequest) {
        // Do not queue duplicate launches behind an asynchronous startActivity call.
        if (!launchMutex.tryLock()) {
            onError(Strings.graphical_runtime_launch_in_progress.strOr(context))
            return
        }
        val signal = GraphicalRuntimeLaunchSignal()
        try {
            val previousStopped = withContext(Dispatchers.IO) { processes.stopPrevious() }
            if (!previousStopped) {
                onError(Strings.graphical_runtime_previous_process_not_stopped.strOr(context))
                return
            }
            var failed = false
            val reportError: (String) -> Unit = { message -> failed = true; onError(message) }
            val start: (Intent) -> Unit = { intent ->
                signal.attachTo(intent)
                activityStarter(intent)
            }
            // Every adapter runs AFTER the shared process barrier, including staging.
            when (request) {
                is GraphicalRuntimeLaunchRequest.Sdl ->
                    ContextCompileSdlLauncher(context, reportError, start).open(request)
                is GraphicalRuntimeLaunchRequest.NativeActivity ->
                    ContextCompileNativeActivityLauncher(context, reportError, start).open(request)
            }
            if (!failed && signal.wasLaunched && !signal.awaitStarted()) {
                onError(Strings.graphical_runtime_start_not_acknowledged.strOr(context))
            }
        } finally {
            // A cancelled/timed-out Intent may still be queued in ActivityTaskManager.
            // Revoke its ticket BEFORE another launch may replace staging files.
            signal.cancelPending()
            launchMutex.unlock()
        }
    }

    private companion object {
        val launchMutex = Mutex()
    }
}
