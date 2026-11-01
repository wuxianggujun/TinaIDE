package com.wuxianggujun.tinaide.ui

import android.content.Context
import android.content.Intent
import com.wuxianggujun.tinaide.core.terminal.TerminalBackend
import com.wuxianggujun.tinaide.ui.runtime.GraphicalRuntimeLaunchRequest
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

class CompileUiEventObserver(
    private val toastPresenter: ToastPresenter,
    private val graphicalRuntimeLauncher: GraphicalRuntimeLauncher,
    private val terminalLauncher: TerminalLauncher,
    private val projectTreeRevealer: ProjectTreeRevealer,
) {
    interface ToastPresenter {
        fun show(message: String, type: CompileActionsHelper.ToastType)
    }

    fun interface GraphicalRuntimeLauncher {
        suspend fun open(request: GraphicalRuntimeLaunchRequest)
    }

    interface TerminalLauncher {
        fun open(command: String, workDir: String?, backend: TerminalBackend)
    }

    interface ProjectTreeRevealer {
        suspend fun reveal(file: File, selectTarget: Boolean)
    }

    suspend fun handleUiEvent(event: CompileActionsHelper.UiEvent) {
        when (event) {
            is CompileActionsHelper.UiEvent.ShowToast -> {
                toastPresenter.show(event.message, event.type)
            }

            is CompileActionsHelper.UiEvent.OpenGraphicalRuntime -> {
                launchOnce(event.ticket) {
                    graphicalRuntimeLauncher.open(event.request)
                }
            }

            is CompileActionsHelper.UiEvent.OpenTerminal -> {
                launchOnce(event.ticket) {
                    terminalLauncher.open(event.command, event.workDir, event.backend)
                }
            }

            is CompileActionsHelper.UiEvent.RevealInProjectTree -> {
                projectTreeRevealer.reveal(event.file, event.selectTarget)
            }
        }
    }

    private suspend fun launchOnce(ticket: CompileActionGate.Ticket, launch: suspend () -> Unit) {
        if (!ticket.claimLaunch()) return
        try {
            coroutineScope {
                val running = async(start = CoroutineStart.UNDISPATCHED) { launch() }
                select<Unit> {
                    running.onAwait { }
                    ticket.cancelled.onAwait { running.cancel() }
                }
            }
        } finally {
            ticket.finish()
        }
    }
}
class LambdaCompileToastPresenter(
    private val onSuccess: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onInfo: (String) -> Unit,
) : CompileUiEventObserver.ToastPresenter {
    override fun show(message: String, type: CompileActionsHelper.ToastType) {
        when (type) {
            CompileActionsHelper.ToastType.SUCCESS -> onSuccess(message)
            CompileActionsHelper.ToastType.ERROR -> onError(message)
            CompileActionsHelper.ToastType.INFO -> onInfo(message)
        }
    }
}
class ContextCompileTerminalLauncher(
    private val context: Context,
    private val activityStarter: (Intent) -> Unit = { intent -> context.startActivity(intent) },
) : CompileUiEventObserver.TerminalLauncher {
    override fun open(command: String, workDir: String?, backend: TerminalBackend) {
        val intent = Intent(context, TerminalActivity::class.java).apply {
            putExtra(TerminalActivity.EXTRA_COMMAND, command)
            putExtra(TerminalActivity.EXTRA_BACKEND, backend.name.lowercase())
            // Run/Terminal 动作需要一个干净的 shell 会话：命令里包含 `exit`，不能污染用户现有终端。
            putExtra(TerminalActivity.EXTRA_NEW_SESSION, true)
            workDir?.let { workingDirectory ->
                putExtra(TerminalActivity.EXTRA_WORK_DIR, workingDirectory)
                putExtra(TerminalActivity.EXTRA_PROJECT_PATH, workingDirectory)
            }
        }
        activityStarter(intent)
    }
}
