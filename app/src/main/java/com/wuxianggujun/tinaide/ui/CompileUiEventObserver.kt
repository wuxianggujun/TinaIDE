package com.wuxianggujun.tinaide.ui

import android.content.Context
import android.content.Intent
import com.wuxianggujun.tinaide.core.compile.RunConfiguration
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.terminal.TerminalBackend
import com.wuxianggujun.tinaide.ui.runtime.SdlRuntimeLibraryStager
import com.wuxianggujun.tinaide.ui.sdl.ExternalSdlActivity
import com.wuxianggujun.tinaide.ui.sdl.SdlRuntimeResolver
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CompileUiEventObserver(
    private val toastPresenter: ToastPresenter,
    private val sdlLauncher: SdlLauncher,
    private val terminalLauncher: TerminalLauncher,
    private val projectTreeRevealer: ProjectTreeRevealer,
) {
    interface ToastPresenter {
        fun show(message: String, type: CompileActionsHelper.ToastType)
    }

    fun interface SdlLauncher {
        suspend fun open(libraryPath: String, environment: Map<String, String>)
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

            is CompileActionsHelper.UiEvent.OpenSdl -> {
                sdlLauncher.open(event.libraryPath, event.environment)
            }

            is CompileActionsHelper.UiEvent.OpenTerminal -> {
                terminalLauncher.open(event.command, event.workDir, event.backend)
            }

            is CompileActionsHelper.UiEvent.RevealInProjectTree -> {
                projectTreeRevealer.reveal(event.file, event.selectTarget)
            }
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
class ContextCompileSdlLauncher(
    private val context: Context,
    private val runConfigurationProvider: () -> RunConfiguration,
    private val onError: (String) -> Unit,
    private val activityStarter: (Intent) -> Unit = { intent -> context.startActivity(intent) },
) : CompileUiEventObserver.SdlLauncher {
    override suspend fun open(libraryPath: String, environment: Map<String, String>) {
        val normalizedLibraryPath = libraryPath.trim()
        validateSharedLibraryPath(normalizedLibraryPath)?.let { message ->
            onError(message)
            return
        }

        val runConfig = runConfigurationProvider()
        val runtime = withContext(Dispatchers.IO) {
            SdlRuntimeResolver.resolve(
                context = context,
                mainLibraryPath = normalizedLibraryPath,
                extraRuntimeLibDirs = launchRuntimeDirs(environment),
                allowUndetectedSdl = true,
            )
        }
        when (runtime) {
            is SdlRuntimeResolver.ResolveResult.Sdl -> launchSdlRuntime(
                libraryPath = normalizedLibraryPath,
                runtime = runtime,
                runConfig = runConfig,
                launchEnvironment = environment,
            )

            SdlRuntimeResolver.ResolveResult.NonSdl -> Unit

            is SdlRuntimeResolver.ResolveResult.Error -> onError(runtime.message)
        }
    }

    private fun launchRuntimeDirs(environment: Map<String, String>): List<File> = environment["LD_LIBRARY_PATH"]
        .orEmpty()
        .split(':')
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .map(::File)
        .toList()

    private fun validateSharedLibraryPath(libraryPath: String): String? {
        if (libraryPath.isBlank()) {
            return Strings.sdl_runtime_error_main_library_missing.strOr(context)
        }
        if (!File(libraryPath).name.endsWith(".so", ignoreCase = true)) {
            return Strings.sdl_runtime_invalid_shared_library.strOr(context, libraryPath)
        }
        return null
    }

    private suspend fun launchSdlRuntime(
        libraryPath: String,
        runtime: SdlRuntimeResolver.ResolveResult.Sdl,
        runConfig: RunConfiguration,
        launchEnvironment: Map<String, String>,
    ) {
        val staged = withContext(Dispatchers.IO) {
            SdlRuntimeLibraryStager.stage(
                context = context,
                mainLibraryPath = libraryPath,
                preloadLibraryPaths = runtime.spec.preloadLibraryPaths
            )
        }
        when (staged) {
            is SdlRuntimeLibraryStager.StageResult.Error -> {
                onError(Strings.sdl_runtime_stage_failed.strOr(context, staged.message))
            }

            is SdlRuntimeLibraryStager.StageResult.Success -> {
                val intent = ExternalSdlActivity.createIntent(
                    context = context,
                    sdlLibraryPath = runtime.spec.sdlLibraryPath,
                    mainLibraryPath = staged.runtime.mainLibraryPath,
                    requiredSdlMajor = runtime.spec.requiredSdlMajor,
                    preloadLibraryPaths = staged.runtime.preloadLibraryPaths,
                    sdlOrientation = runConfig.sdlOrientation,
                    enableFloatingLog = runConfig.enableFloatingLog,
                    launchEnvironment = launchEnvironment,
                )
                runCatching { activityStarter(intent) }
                    .onFailure { throwable ->
                        onError(
                            Strings.sdl_runtime_error_launch_failed.strOr(
                                context,
                                throwable.message ?: throwable.javaClass.simpleName
                            )
                        )
                    }
            }
        }
    }
}
