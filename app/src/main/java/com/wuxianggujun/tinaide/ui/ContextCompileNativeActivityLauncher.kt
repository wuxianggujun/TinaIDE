package com.wuxianggujun.tinaide.ui

import android.content.Context
import android.content.Intent
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.ui.nativeactivity.ExternalNativeActivity
import com.wuxianggujun.tinaide.ui.nativeactivity.NativeActivityRuntimeResolver
import com.wuxianggujun.tinaide.ui.nativeactivity.NativeActivityRuntimeStager
import com.wuxianggujun.tinaide.ui.runtime.GraphicalRuntimeLaunchRequest
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class ContextCompileNativeActivityLauncher(
    private val context: Context,
    private val onError: (String) -> Unit,
    private val activityStarter: (Intent) -> Unit = { intent -> context.startActivity(intent) },
) {

    suspend fun open(request: GraphicalRuntimeLaunchRequest.NativeActivity) {
        val runtime = withContext(Dispatchers.IO) {
            NativeActivityRuntimeResolver.resolve(
                context = context,
                mainLibraryPath = request.libraryPath.trim(),
                extraRuntimeLibDirs = launchRuntimeDirs(request.environment),
            )
        }
        if (runtime is NativeActivityRuntimeResolver.ResolveResult.Error) {
            onError(runtime.message)
            return
        }

        val spec = (runtime as NativeActivityRuntimeResolver.ResolveResult.Success).spec
        val staged = withContext(Dispatchers.IO) {
            NativeActivityRuntimeStager.stage(context, spec)
        }
        if (staged is NativeActivityRuntimeStager.StageResult.Error) {
            onError(
                Strings.native_activity_runtime_stage_failed.strOr(context, staged.message)
            )
            return
        }

        val stagedRuntime = (staged as NativeActivityRuntimeStager.StageResult.Success).runtime
        val intent = ExternalNativeActivity.createIntent(
            context = context,
            mainLibraryPath = stagedRuntime.mainLibraryPath,
            dependencyLibraryPaths = stagedRuntime.dependencyLibraryPaths,
            enableFloatingLog = request.enableFloatingLog,
            launchEnvironment = request.environment,
        )
        runCatching { activityStarter(intent) }
            .onFailure { error ->
                onError(
                    Strings.native_activity_runtime_launch_failed.strOr(
                        context,
                        error.message ?: error.javaClass.simpleName,
                    )
                )
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
}
