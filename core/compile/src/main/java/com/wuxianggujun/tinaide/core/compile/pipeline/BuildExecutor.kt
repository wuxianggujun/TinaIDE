package com.wuxianggujun.tinaide.core.compile.pipeline

import com.wuxianggujun.tinaide.core.compile.ConfigureResult
import com.wuxianggujun.tinaide.core.compile.event.BuildEvent
import com.wuxianggujun.tinaide.core.compile.event.BuildEventEmitter
import com.wuxianggujun.tinaide.core.compile.strategy.BuildContext
import com.wuxianggujun.tinaide.core.compile.strategy.ExecutionOutcome

/**
 * 构建执行器:把 [BuildPlan.Build] 转发到对应 [com.wuxianggujun.tinaide.core.compile.strategy.BuildStrategy]。
 *
 * 也负责 [BuildPlan.CleanOnly] 的清理分派。事件发射由 Strategy 内部细粒度发出,
 * 这里只保留顶层 "Cleaned" 事件。
 *
 * 无状态;可做 single 注入。
 */
class BuildExecutor {

    suspend fun execute(
        plan: BuildPlan.Build,
        ctx: BuildContext,
        emitter: BuildEventEmitter,
    ): ExecutionOutcome {
        val executionContext = ctx.copy(buildReason = plan.reason)
        return plan.strategy.execute(executionContext, plan.spec, plan.fingerprint, emitter)
    }

    suspend fun clean(
        plan: BuildPlan.CleanOnly,
        ctx: BuildContext,
        emitter: BuildEventEmitter,
    ): Int {
        plan.strategy.clean(ctx, plan.reconfigure)
        // 目前策略 clean 不返回清理条数;如未来需要可改成 Strategy 暴露 Cleaned 返回值
        emitter.emit(BuildEvent.Build.Cleaned(0))
        return 0
    }

    suspend fun configureOnly(
        plan: BuildPlan.ConfigureOnly,
        ctx: BuildContext,
        emitter: BuildEventEmitter,
    ): ConfigureResult {
        emitter.emit(BuildEvent.Build.ConfigureStarted(ctx.target))
        val start = System.currentTimeMillis()
        val result = plan.strategy.configureOnly(ctx)
        when (result) {
            is ConfigureResult.Success ->
                emitter.emit(BuildEvent.Build.ConfigureCompleted(System.currentTimeMillis() - start))
            is ConfigureResult.Error ->
                emitter.emit(BuildEvent.Build.ConfigureFailed(result.message))
        }
        return result
    }
}
