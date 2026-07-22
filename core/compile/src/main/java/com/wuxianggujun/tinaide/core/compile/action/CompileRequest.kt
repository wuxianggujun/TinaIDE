package com.wuxianggujun.tinaide.core.compile.action

import com.wuxianggujun.tinaide.core.compile.OutputMode

/**
 * 编译/启动请求,由 UI 层构造,送入 `BuildOrchestrator.run(...)`。
 *
 * 将"构建意图"与"启动意图"正交分解,消除旧 `ExecutionMode` 的语义耦合。
 *
 * @property build 构建意图(None/IfNeeded/Force/Clean)
 * @property launch 启动意图(None/Run/Debug/Terminal)
 * @property target 目标名(CMake target / 单文件名),null 走默认解析
 * @property fallbackOnLaunchFailure 缓存产物启动失败时自动触发 Force 重建重试(详见设计文档 §4.6)
 */
data class CompileRequest(
    val build: BuildIntent,
    val launch: LaunchIntent,
    val target: String? = null,
    val fallbackOnLaunchFailure: Boolean = true,
) {
    companion object {
        /** 标准 Run:智能构建 + 终端运行 + Auto-Fallback 开启 */
        fun run(outputMode: OutputMode = OutputMode.TERMINAL): CompileRequest = CompileRequest(BuildIntent.IfNeeded, LaunchIntent.Run(outputMode))

        /** 显式 Build(不启动) */
        fun buildOnly(): CompileRequest = CompileRequest(BuildIntent.Force, LaunchIntent.None)

        /** 长按 Run 菜单 / 显式「Rebuild & Run」:强制重建 + 禁用 fallback(已经是强制重建,fallback 无意义) */
        fun forceRun(outputMode: OutputMode = OutputMode.TERMINAL): CompileRequest = CompileRequest(
            build = BuildIntent.Force,
            launch = LaunchIntent.Run(outputMode),
            fallbackOnLaunchFailure = false,
        )

        /** 调试:智能构建 + Debug 启动 */
        fun debug(): CompileRequest = CompileRequest(BuildIntent.IfNeeded, LaunchIntent.Debug)

        /** 在终端中运行 */
        fun terminal(): CompileRequest = CompileRequest(BuildIntent.IfNeeded, LaunchIntent.Terminal())

        /** 清理构建产物 */
        fun clean(reconfigure: Boolean = false): CompileRequest = CompileRequest(BuildIntent.Clean(reconfigure), LaunchIntent.None)

        /** 仅启动缓存产物(不构建),Planner 找不到缓存时会 Invalid */
        fun launchOnly(outputMode: OutputMode = OutputMode.TERMINAL): CompileRequest = CompileRequest(
            build = BuildIntent.None,
            launch = LaunchIntent.Run(outputMode),
            fallbackOnLaunchFailure = false,
        )
    }
}
