package com.wuxianggujun.tinaide.core.compile.pipeline

import com.wuxianggujun.tinaide.core.compile.BuildDiagnosticsLog
import com.wuxianggujun.tinaide.core.compile.OutputMode
import com.wuxianggujun.tinaide.core.compile.action.LaunchIntent
import com.wuxianggujun.tinaide.core.compile.artifact.Artifact
import com.wuxianggujun.tinaide.core.compile.event.BuildEvent
import com.wuxianggujun.tinaide.core.compile.event.BuildEventEmitter
import com.wuxianggujun.tinaide.core.compile.event.BuildReport
import com.wuxianggujun.tinaide.core.compile.launcher.DebugLauncher
import com.wuxianggujun.tinaide.core.compile.launcher.LaunchOutcome
import com.wuxianggujun.tinaide.core.compile.launcher.NativeActivityLauncher
import com.wuxianggujun.tinaide.core.compile.launcher.SdlLauncher
import com.wuxianggujun.tinaide.core.compile.launcher.TerminalLauncher
import com.wuxianggujun.tinaide.core.compile.strategy.BuildContext

/**
 * Launch 分派器:按 [LaunchIntent] 路由到具体 [com.wuxianggujun.tinaide.core.compile.launcher.Launcher]。
 *
 * 统一:
 * - 发射 `Launch.Started`(避免各 Launcher 重复发)
 * - 把 [LaunchOutcome] 封装为 [BuildReport.Success] / [BuildReport.LaunchFailed]
 * - 透传 `artifactWasCached`(Auto-Fallback 决策必需)
*/
class LaunchDispatcher(
    private val sdlLauncher: SdlLauncher,
    private val nativeActivityLauncher: NativeActivityLauncher,
    private val debugLauncher: DebugLauncher,
    private val terminalLauncher: TerminalLauncher,
) {

    suspend fun dispatch(
        intent: LaunchIntent,
        artifact: Artifact,
        artifactWasCached: Boolean,
        ctx: BuildContext,
        emitter: BuildEventEmitter,
    ): BuildReport {
        require(intent !is LaunchIntent.None) {
            "LaunchDispatcher.dispatch should not be called with LaunchIntent.None"
        }
        BuildDiagnosticsLog.i {
            "launch dispatch start intent=${intent.diagnosticsName()} outputMode=${(intent as? LaunchIntent.Run)?.outputMode ?: ""} " +
                "artifact=${artifact.absolutePath} kind=${artifact.kind} target=${artifact.id.targetName} cached=$artifactWasCached " +
                "contentHash=${artifact.contentHash.shortHash()} trackedHash=${artifact.fingerprint.trackedInputsHash.shortHash()} " +
                "sysroot=${artifact.fingerprint.sysrootProfileId.orEmpty()} api=${artifact.fingerprint.sysrootApiLevel}"
        }
        emitter.emit(BuildEvent.Launch.Started(artifact))

        val outcome: LaunchOutcome = when (intent) {
            is LaunchIntent.Run -> when (intent.outputMode) {
                OutputMode.TERMINAL -> terminalLauncher.launch(artifact, ctx, emitter)
                OutputMode.SDL -> sdlLauncher.launch(artifact, ctx, emitter)
                OutputMode.NATIVE_ACTIVITY -> nativeActivityLauncher.launch(artifact, ctx, emitter)
            }
            LaunchIntent.Debug -> debugLauncher.launch(artifact, ctx, emitter)
            is LaunchIntent.Terminal -> terminalLauncher.launchWithWorkingDir(
                artifact = artifact,
                workingDir = intent.workingDir,
                ctx = ctx,
                emitter = emitter,
            )
            LaunchIntent.None -> error("unreachable (pre-checked above)")
        }

        return when (outcome) {
            is LaunchOutcome.Prepared -> {
                BuildDiagnosticsLog.i {
                    "launch dispatch prepared descriptor=${outcome.descriptor::class.simpleName} " +
                        "artifact=${artifact.absolutePath} cached=$artifactWasCached"
                }
                BuildReport.Success(
                    artifact = artifact,
                    descriptor = outcome.descriptor,
                    summary = if (artifactWasCached) "launched from cached artifact" else "launched freshly built artifact",
                )
            }
            is LaunchOutcome.Failed -> {
                BuildDiagnosticsLog.w {
                    "launch dispatch failed intent=${intent.diagnosticsName()} artifact=${artifact.absolutePath} " +
                        "cached=$artifactWasCached reason=${outcome.reason}"
                }
                BuildReport.LaunchFailed(
                    reason = outcome.reason,
                    artifact = artifact,
                    artifactWasCached = artifactWasCached,
                )
            }
        }
    }

    private fun LaunchIntent.diagnosticsName(): String = when (this) {
        is LaunchIntent.Run -> "Run"
        LaunchIntent.Debug -> "Debug"
        is LaunchIntent.Terminal -> "Terminal"
        LaunchIntent.None -> "None"
    }

    private fun String.shortHash(): String = take(12)
}
