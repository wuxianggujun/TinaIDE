package com.wuxianggujun.tinaide.ui.nativeactivity

import android.content.Context
import com.wuxianggujun.tinaide.ui.runtime.NativeRuntimeStagingArea
import com.wuxianggujun.tinaide.ui.runtime.canonicalRuntimeFile
import java.io.File
import timber.log.Timber

/** Copies a NativeActivity runtime closure into executable app-private storage. */
object NativeActivityRuntimeStager {
    private const val TAG = "NativeActivityStager"

    data class StagedRuntime(
        val mainLibraryPath: String,
        val dependencyLibraryPaths: List<String>,
    )

    sealed class StageResult {
        data class Success(val runtime: StagedRuntime) : StageResult()
        data class Error(val message: String, val throwable: Throwable? = null) : StageResult()
    }

    fun stage(
        context: Context,
        spec: NativeActivityRuntimeResolver.RuntimeSpec,
    ): StageResult = stage(
        stageRoot = File(context.filesDir, "run-bin/native-activity"),
        spec = spec,
    )

    @Synchronized
    internal fun stage(
        stageRoot: File,
        spec: NativeActivityRuntimeResolver.RuntimeSpec,
    ): StageResult = runCatching {
        val main = canonicalRuntimeFile(spec.mainLibrary)
        // ExternalNativeActivity runs in a disposable process, so a stable per-project path is
        // sufficient. Clearing it on every launch avoids accumulating one directory per rebuild.
        val stagingArea = NativeRuntimeStagingArea.prepare(
            stageRootDir = stageRoot,
            identityFile = main,
            runtimeName = "NativeActivity",
        )

        val stagedDependencies = spec.dependencyLibraries.map(stagingArea::copy)
        val stagedMain = stagingArea.copy(main)
        Timber.tag(TAG).i(
            "Staged NativeActivity runtime: main=%s dependencies=%d dir=%s",
            stagedMain.name,
            stagedDependencies.size,
            stagingArea.directory.absolutePath,
        )
        StageResult.Success(
            StagedRuntime(
                mainLibraryPath = stagedMain.absolutePath,
                dependencyLibraryPaths = stagedDependencies.map(File::getAbsolutePath),
            )
        )
    }.getOrElse { error ->
        Timber.tag(TAG).e(error, "Failed to stage NativeActivity runtime")
        StageResult.Error(
            message = error.message ?: error.javaClass.simpleName,
            throwable = error,
        )
    }

}
