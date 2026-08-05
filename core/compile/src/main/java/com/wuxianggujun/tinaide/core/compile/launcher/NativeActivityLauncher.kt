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

/** Prepares a shared library for the Android NativeActivity runtime host. */
class NativeActivityLauncher : Launcher {

    companion object {
        private const val TAG = "NativeActivityLauncher"
    }

    override suspend fun launch(
        artifact: Artifact,
        ctx: BuildContext,
        emitter: BuildEventEmitter,
    ): LaunchOutcome {
        val file = File(artifact.absolutePath)
        if (artifact.kind != ArtifactKind.SHARED_LIBRARY || !file.name.endsWith(".so", ignoreCase = true)) {
            val reason = Strings.native_activity_runtime_invalid_shared_library.strOr(
                ctx.appContext,
                artifact.absolutePath,
            )
            emitter.emit(BuildEvent.Launch.Failed(reason, wasArtifactCached = false))
            return LaunchOutcome.Failed(reason)
        }

        Timber.tag(TAG).d("NativeActivity launch prepared: %s", file.absolutePath)
        emitter.emit(BuildEvent.Launch.Completed)
        return LaunchOutcome.Prepared(
            LaunchDescriptor.NativeActivity(
                artifact = artifact,
                libraryPath = artifact.absolutePath,
            )
        )
    }
}
