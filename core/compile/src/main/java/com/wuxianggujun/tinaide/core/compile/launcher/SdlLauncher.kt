package com.wuxianggujun.tinaide.core.compile.launcher

import com.wuxianggujun.tinaide.core.compile.artifact.Artifact
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactKind
import com.wuxianggujun.tinaide.core.compile.event.BuildEvent
import com.wuxianggujun.tinaide.core.compile.event.BuildEventEmitter
import com.wuxianggujun.tinaide.core.compile.strategy.BuildContext
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import java.io.File
import timber.log.Timber

/**
 * Prepares a `.so` artifact before the UI starts the SDL graphical runtime.
 *
 * 启动前检查产物是否导出 SDL_main：缺失时以 Launch.Failed 结束，让原因进入
 * Build Log 与失败报告，而不是宿主进程起来后 dlsym 失败、屏幕一闪而过。
 */
class SdlLauncher : Launcher {

    companion object {
        private const val TAG = "SdlLauncher"
    }

    override suspend fun launch(
        artifact: Artifact,
        ctx: BuildContext,
        emitter: BuildEventEmitter,
    ): LaunchOutcome {
        val file = File(artifact.absolutePath)
        if (artifact.kind != ArtifactKind.SHARED_LIBRARY || !file.name.endsWith(".so", ignoreCase = true)) {
            val reason = Strings.sdl_runtime_invalid_shared_library.strOr(ctx.appContext, artifact.absolutePath)
            emitter.emit(BuildEvent.Launch.Failed(reason, wasArtifactCached = false))
            return LaunchOutcome.Failed(reason)
        }
        SdlEntryPoint.entryPointErrorMessage(ctx.appContext, file)?.let { reason ->
            Timber.tag(TAG).w("SDL entry point missing: %s", file.absolutePath)
            emitter.emit(BuildEvent.Launch.Failed(reason, wasArtifactCached = false))
            return LaunchOutcome.Failed(reason)
        }
        Timber.tag(TAG).d("SDL graphical launch prepared: %s", file.absolutePath)
        emitter.emit(BuildEvent.Launch.Completed)
        return LaunchOutcome.Prepared(LaunchDescriptor.Sdl(artifact = artifact, libraryPath = artifact.absolutePath))
    }
}
