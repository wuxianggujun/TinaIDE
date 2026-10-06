package com.wuxianggujun.tinaide.core.compile.strategy

import com.wuxianggujun.tinaide.core.compile.BuildSystem
import com.wuxianggujun.tinaide.core.compile.ConfigureResult
import com.wuxianggujun.tinaide.core.compile.TargetInfo
import com.wuxianggujun.tinaide.core.compile.artifact.ArtifactSpec
import com.wuxianggujun.tinaide.core.compile.artifact.BuildFingerprint
import com.wuxianggujun.tinaide.core.compile.event.BuildEventEmitter
import java.io.File

/**
 * 构建策略接口。
 *
 * - 引入 [describeOutput]:纯查询(允许只读 IO),告诉 Planner 产物长什么样,不得 spawn 构建
 * - [execute] 返回 [ExecutionOutcome],Success 携带完整 [com.wuxianggujun.tinaide.core.compile.artifact.Artifact]
 * - 事件经由 [BuildEventEmitter] 下发,而不是回调 `onProgress`
 * - [clean] 带 reconfigure 开关(CMake 用)
 */
interface BuildStrategy {

    /** 该策略对应的构建系统。 */
    val buildSystem: BuildSystem

    /** 能否处理给定项目根目录(仅文件系统探测,不跑构建)。 */
    suspend fun canHandle(projectRoot: File): Boolean

    /**
     * 描述 target 对应的产物规格。**允许做只读 IO(如读 CMake File API)**,
     * 但禁止触发 configure/build。
     *
     * 返回 null 表示"无法描述"(如 target 不存在、CMake 未 configure 过等),
     * Planner 会据此返回 `BuildPlan.Invalid`。
     */
    suspend fun describeOutput(ctx: BuildContext): ArtifactSpec?

    /**
     * 执行实际构建。必须在返回前把产物二进制的 hash 与 sources mtime 填进
     * [com.wuxianggujun.tinaide.core.compile.artifact.Artifact],由
     * Orchestrator 写入 ArtifactStore。
     *
     * @param spec 来自 [describeOutput] 的同一次结果,避免 Strategy 重新计算
     * @param fingerprint Planner 基于 spec + options 算好的指纹,直接写入产物
     * @param emitter 用于发射 Build.Configure/Compile/Progress 事件
     */
    suspend fun execute(
        ctx: BuildContext,
        spec: ArtifactSpec,
        fingerprint: BuildFingerprint,
        emitter: BuildEventEmitter,
    ): ExecutionOutcome

    /**
     * 清理构建产物。
     *
     * @param reconfigure 若为 true 且策略支持(如 CMake),清理后立即重新 configure;
     *                    否则仅删除产物文件。其它策略应忽略该参数。
     */
    suspend fun clean(ctx: BuildContext, reconfigure: Boolean = false)

    /**
     * 仅重新 configure,重生成编译数据库(如 `compile_commands.json`),**不触发编译**。
     *
     * 默认返回 [ConfigureResult.Error] 表示当前策略不支持;只有 CMake 策略需要覆写。
     */
    suspend fun configureOnly(ctx: BuildContext): ConfigureResult =
        ConfigureResult.Error("configureOnly is not supported by $buildSystem")

    /** 列出可构建的目标(单文件策略列所有源文件,CMake 策略列所有 target)。 */
    suspend fun getTargets(ctx: BuildContext): List<TargetInfo> = emptyList()
}
