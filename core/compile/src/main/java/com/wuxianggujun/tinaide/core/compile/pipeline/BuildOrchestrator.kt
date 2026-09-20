package com.wuxianggujun.tinaide.core.compile.pipeline

import com.wuxianggujun.tinaide.core.compile.action.BuildIntent
import com.wuxianggujun.tinaide.core.compile.action.CompileRequest
import com.wuxianggujun.tinaide.core.compile.action.LaunchIntent
import com.wuxianggujun.tinaide.core.compile.artifact.Artifact
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactStore
import com.wuxianggujun.tinaide.core.compile.event.BuildEvent
import com.wuxianggujun.tinaide.core.compile.event.BuildEventEmitter
import com.wuxianggujun.tinaide.core.compile.event.BuildReport
import com.wuxianggujun.tinaide.core.compile.strategy.BuildContext
import com.wuxianggujun.tinaide.core.compile.strategy.ExecutionOutcome
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * 顶层编排器:接收 [CompileRequest] + [BuildContext],返回最终 [BuildReport]。
 *
 * 职责:
 * - 调用 [EnvironmentValidator] → [BuildPlanner] → [BuildExecutor] → [LaunchDispatcher] 串起完整流水线
 * - 在 [BuildPlan.Build] 成功后写入 [ArtifactStore]
 * - 实现 **Auto-Fallback**(见设计文档 §4.6):
 *   缓存产物启动失败时,自动 Force 重试一次。通过 `fallbackOnLaunchFailure = false` 锁死二次回退,防死循环。
 * - 以 [Mutex] 串行化同一个 orchestrator 的并发请求,避免同一 buildDir 并发构建冲突。
 *   多项目并发由不同 buildDir 的不同实例自然隔离(DI 可 factory 注入)。
 */
class BuildOrchestrator(
    private val validator: EnvironmentValidator,
    private val planner: BuildPlanner,
    private val executor: BuildExecutor,
    private val dispatcher: LaunchDispatcher,
    private val artifactStore: ArtifactStore,
    private val events: BuildEventEmitter,
) {

    companion object {
        private const val TAG = "BuildOrchestrator"
    }

    private val mutex = Mutex()

    suspend fun run(request: CompileRequest, context: BuildContext): BuildReport = mutex.withLock {
        val initial = runOnce(request, context)

        if (!shouldFallbackToRebuild(request, initial)) return@withLock initial

        val failure = initial as BuildReport.LaunchFailed
        Timber.tag(TAG).w(
            "AutoFallback triggered: reason=%s, cachedArtifact=%s",
            failure.reason,
            failure.artifact.absolutePath,
        )
        events.emit(
            BuildEvent.AutoFallback(
                reason = "cached artifact launch failed: ${failure.reason}",
                firstFailure = failure,
            )
        )

        val forceRequest = request.copy(
            build = BuildIntent.Force,
            fallbackOnLaunchFailure = false,
        )
        val fallbackReport = runOnce(forceRequest, context)
        Timber.tag(TAG).d(
            "AutoFallback completed: outcome=%s",
            fallbackReport::class.simpleName,
        )
        fallbackReport
    }

    private fun shouldFallbackToRebuild(request: CompileRequest, report: BuildReport): Boolean = request.fallbackOnLaunchFailure &&
        request.build is BuildIntent.IfNeeded &&
        request.launch !is LaunchIntent.None &&
        report is BuildReport.LaunchFailed &&
        report.artifactWasCached

    private suspend fun runOnce(request: CompileRequest, ctx: BuildContext): BuildReport {
        events.emit(BuildEvent.Started(request))

        validator.validate(request, ctx)?.let { err ->
            return finishWith(BuildReport.Invalid(err))
        }

        events.emit(BuildEvent.Planning.Started)
        val plan = planner.plan(request, ctx)
        events.emit(BuildEvent.Planning.Decided(describePlan(plan)))

        val (artifact, wasCached) = when (plan) {
            is BuildPlan.Skip -> {
                events.emit(BuildEvent.Build.Skipped(plan.artifact, plan.reason))
                plan.artifact to true
            }
            is BuildPlan.Build -> {
                when (val outcome = executor.execute(plan, ctx, events)) {
                    is ExecutionOutcome.Success -> {
                        artifactStore.register(outcome.artifact, ctx.buildDir)
                        outcome.artifact to false
                    }
                    is ExecutionOutcome.Failure -> {
                        return finishWith(BuildReport.BuildFailed(outcome.reason, outcome.diagnostics))
                    }
                }
            }
            is BuildPlan.CleanOnly -> {
                val cleared = executor.clean(plan, ctx, events)
                return finishWith(BuildReport.Cleaned(cleared))
            }
            is BuildPlan.ConfigureOnly -> {
                return when (val result = executor.configureOnly(plan, ctx, events)) {
                    is com.wuxianggujun.tinaide.core.compile.ConfigureResult.Success ->
                        finishWith(BuildReport.Reconfigured(result.compileCommandsPath?.absolutePath))
                    is com.wuxianggujun.tinaide.core.compile.ConfigureResult.Error ->
                        finishWith(BuildReport.BuildFailed(result.message))
                }
            }
            is BuildPlan.Invalid -> return finishWith(BuildReport.Invalid(plan.reason))
        }

        val report = dispatchLaunch(request.launch, artifact, wasCached, ctx)
        return finishWith(report)
    }

    private suspend fun dispatchLaunch(
        intent: LaunchIntent,
        artifact: Artifact,
        wasCached: Boolean,
        ctx: BuildContext,
    ): BuildReport = if (intent is LaunchIntent.None) {
        BuildReport.BuiltOnly(artifact)
    } else {
        dispatcher.dispatch(intent, artifact, wasCached, ctx, events)
    }

    private suspend fun finishWith(report: BuildReport): BuildReport {
        when (report) {
            is BuildReport.Success -> Timber.tag(TAG).i(
                "Build finished: report=Success artifact=%s launch=%s summary=%s",
                report.artifact.absolutePath,
                report.descriptor::class.simpleName,
                report.summary,
            )
            is BuildReport.BuiltOnly -> Timber.tag(TAG).i(
                "Build finished: report=BuiltOnly artifact=%s",
                report.artifact.absolutePath,
            )
            is BuildReport.BuildFailed -> Timber.tag(TAG).w(
                "Build finished: report=BuildFailed reason=%s",
                report.reason,
            )
            is BuildReport.LaunchFailed -> Timber.tag(TAG).w(
                "Build finished: report=LaunchFailed reason=%s artifact=%s cached=%s",
                report.reason,
                report.artifact.absolutePath,
                report.artifactWasCached,
            )
            is BuildReport.Cleaned -> Timber.tag(TAG).i(
                "Build finished: report=Cleaned clearedCount=%s",
                report.clearedCount,
            )
            is BuildReport.Reconfigured -> Timber.tag(TAG).i(
                "Build finished: report=Reconfigured compileCommands=%s",
                report.compileCommandsPath ?: "<none>",
            )
            is BuildReport.Invalid -> Timber.tag(TAG).w(
                "Build finished: report=Invalid reason=%s",
                report.reason,
            )
        }
        events.emit(BuildEvent.Finished(report))
        return report
    }

    private fun describePlan(plan: BuildPlan): String = when (plan) {
        is BuildPlan.Skip -> "skip: ${plan.reason}"
        is BuildPlan.Build -> "build: ${plan.reason}"
        is BuildPlan.CleanOnly -> if (plan.reconfigure) "clean + reconfigure" else "clean"
        is BuildPlan.ConfigureOnly -> "configure only"
        is BuildPlan.Invalid -> "invalid: ${plan.reason}"
    }
}
