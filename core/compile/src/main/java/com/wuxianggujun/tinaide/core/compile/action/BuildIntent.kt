package com.wuxianggujun.tinaide.core.compile.action

/**
 * 构建意图:用户对"是否构建"的选择,与"启动什么"正交。
 *
 * 与 [LaunchIntent] 组合成 [CompileRequest]。
 */
sealed interface BuildIntent {
    /**
     * 不构建,直接使用已有产物启动。
     *
     * 适用于:调试复用场景、手动 build 后只想连续启动。
     * 若缓存产物不存在,Planner 会返回 Invalid。
     */
    data object None : BuildIntent

    /**
     * 智能模式:产物新鲜则跳过构建,否则按需构建。
     *
     * 默认的 Run / Debug 语义走这个分支。
     */
    data object IfNeeded : BuildIntent

    /**
     * 强制重新构建,忽略所有缓存判断。
     *
     * 显式「Rebuild」或长按 Run 走这个分支。
     */
    data object Force : BuildIntent

    /**
     * 清理构建产物与中间文件。
     *
     * @property reconfigure 清理后是否立即 reconfigure(CMake 专用)
     */
    data class Clean(val reconfigure: Boolean = false) : BuildIntent

    /**
     * 仅重新 configure，重生成 `compile_commands.json`，**不触发编译**。
     *
     * 用于编辑器检测到编译数据库过期（如 `CMakeLists.txt` 变更但未构建）后，
     * 让用户一键刷新 clangd 的编译上下文，而不必付出全量编译代价。
     * 仅 CMake 策略支持；其它策略应返回不支持。
     */
    data object ConfigureOnly : BuildIntent
}
