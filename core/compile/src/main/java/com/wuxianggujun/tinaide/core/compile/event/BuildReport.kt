package com.wuxianggujun.tinaide.core.compile.event

import com.wuxianggujun.tinaide.core.compile.BuildDiagnostic
import com.wuxianggujun.tinaide.core.compile.artifact.Artifact
import com.wuxianggujun.tinaide.core.compile.launcher.LaunchDescriptor

/**
 * 顶层执行报告,`BuildOrchestrator.run(...)` 的最终返回值。
 *
 * 用 sealed interface 替代旧 `CompileProjectUseCase.Result`:
 * - 旧 Result 只有 Success/Error,错误原因需要从 userMessage 字符串解析
 * - 新 BuildReport 区分 Build 失败 / Launch 失败 / 输入非法,允许上层针对性处理
 *
 * [LaunchFailed.artifactWasCached] 是 Auto-Fallback 决策的关键字段(见设计文档 §4.6.2)。
 */
sealed interface BuildReport {

    /** 仅构建成功(LaunchIntent.None) */
    data class BuiltOnly(val artifact: Artifact) : BuildReport

    /**
     * 构建 + 启动描述符准备均成功。
     *
     * [descriptor] 由 Launcher 产出,UI 层据此真正拉起终端/SDL/调试(见 Phase B 设计)。
     */
    data class Success(
        val artifact: Artifact,
        val descriptor: LaunchDescriptor,
        val summary: String,
    ) : BuildReport

    /** 清理操作成功 */
    data class Cleaned(val clearedCount: Int) : BuildReport

    /** 仅重新 configure 成功(重生成 compile_commands.json,未编译) */
    data class Reconfigured(val compileCommandsPath: String?) : BuildReport

    /** 构建阶段失败(源码错、配置错、工具链错等) */
    data class BuildFailed(
        val reason: String,
        val diagnostics: List<BuildDiagnostic> = emptyList(),
    ) : BuildReport

    /**
     * 启动阶段失败。
     *
     * [artifactWasCached] 必须真实反映 Planner 是否命中了缓存:
     * - true + request.fallbackOnLaunchFailure == true + BuildIntent.IfNeeded → Orchestrator 会触发 Auto-Fallback
     * - false → 启动失败就是启动失败,不 fallback(避免掩盖 Force 路径的真问题)
     */
    data class LaunchFailed(
        val reason: String,
        val artifact: Artifact,
        val artifactWasCached: Boolean,
    ) : BuildReport

    /** 环境检查失败 / 非法请求(找不到 Strategy、None 意图但无缓存等) */
    data class Invalid(val reason: String) : BuildReport
}
