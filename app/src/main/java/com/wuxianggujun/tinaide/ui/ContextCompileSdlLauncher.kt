package com.wuxianggujun.tinaide.ui

import android.content.Context
import android.content.Intent
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.ui.runtime.GraphicalRuntimeLaunchRequest
import com.wuxianggujun.tinaide.ui.runtime.SdlRuntimeLibraryStager
import com.wuxianggujun.tinaide.ui.sdl.ExternalSdlActivity
import com.wuxianggujun.tinaide.ui.sdl.SdlRuntimeResolver
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class ContextCompileSdlLauncher(
    private val context: Context,
    private val onError: (String) -> Unit,
    private val activityStarter: (Intent) -> Unit = { intent -> context.startActivity(intent) },
) {
    suspend fun open(request: GraphicalRuntimeLaunchRequest.Sdl) {
        val normalizedLibraryPath = request.libraryPath.trim()
        validateSharedLibraryPath(normalizedLibraryPath)?.let { message ->
            onError(message)
            return
        }

        val runtime = withContext(Dispatchers.IO) {
            SdlRuntimeResolver.resolve(
                context = context,
                mainLibraryPath = normalizedLibraryPath,
                extraRuntimeLibDirs = launchRuntimeDirs(request.environment),
                allowUndetectedSdl = true,
                preferredSdlMajor = request.preferredSdlMajor,
            )
        }
        when (runtime) {
            is SdlRuntimeResolver.ResolveResult.Sdl -> launchSdlRuntime(
                request = request,
                libraryPath = normalizedLibraryPath,
                runtime = runtime,
            )

            SdlRuntimeResolver.ResolveResult.NonSdl -> Unit
            is SdlRuntimeResolver.ResolveResult.Error -> onError(runtime.message)
        }
    }

    private fun launchRuntimeDirs(environment: Map<String, String>): List<File> =
        environment["LD_LIBRARY_PATH"]
            .orEmpty()
            .split(':')
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
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
        request: GraphicalRuntimeLaunchRequest.Sdl,
        libraryPath: String,
        runtime: SdlRuntimeResolver.ResolveResult.Sdl,
    ) {
        val staged = withContext(Dispatchers.IO) {
            SdlRuntimeLibraryStager.stage(
                context = context,
                sdlLibraryPath = runtime.spec.sdlLibraryPath,
                mainLibraryPath = libraryPath,
                preSdlLibraryPaths = runtime.spec.preSdlLibraryPaths,
                preloadLibraryPaths = runtime.spec.preloadLibraryPaths,
            )
        }
        when (staged) {
            is SdlRuntimeLibraryStager.StageResult.Error -> {
                onError(Strings.sdl_runtime_stage_failed.strOr(context, staged.message))
            }

            is SdlRuntimeLibraryStager.StageResult.Success -> {
                val intent = ExternalSdlActivity.createIntent(
                    context = context,
                    sdlLibraryPath = staged.runtime.sdlLibraryPath,
                    mainLibraryPath = staged.runtime.mainLibraryPath,
                    requiredSdlMajor = runtime.spec.requiredSdlMajor,
                    preSdlLibraryPaths = staged.runtime.preSdlLibraryPaths,
                    preloadLibraryPaths = staged.runtime.preloadLibraryPaths,
                    sdlOrientation = request.orientation,
                    enableFloatingLog = request.enableFloatingLog,
                    launchEnvironment = request.environment,
                )
                runCatching { activityStarter(intent) }
                    .onFailure { throwable ->
                        onError(
                            Strings.sdl_runtime_error_launch_failed.strOr(
                                context,
                                throwable.message ?: throwable.javaClass.simpleName,
                            )
                        )
                    }
            }
        }
    }
}
