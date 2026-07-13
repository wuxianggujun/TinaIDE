package com.wuxianggujun.tinaide.startup

import com.wuxianggujun.tinaide.core.util.CrashLogPrivacyClassifier

internal enum class AppProcessRole {
    HOST,
    CRASH,
    TOOLCHAIN,
    USER_RUNTIME,
    OTHER,
}

/**
 * TinaIDE 多进程初始化策略的唯一判定入口。
 *
 * 崩溃捕获仍由每个相关进程中的 NativeCrashHandler 负责；该角色只决定
 * Application.onCreate 阶段允许加载多少宿主能力。
 */
internal object AppProcessRoleClassifier {
    private const val CRASH_PROCESS_SUFFIX = ":crash"
    private const val TOOLCHAIN_PROCESS_SUFFIX = ":toolchain"

    fun classify(packageName: String, processName: String): AppProcessRole {
        if (packageName.isBlank() || processName.isBlank()) return AppProcessRole.OTHER
        return when {
            CrashLogPrivacyClassifier.isHostAppProcess(packageName, processName) -> AppProcessRole.HOST
            processName == packageName + CRASH_PROCESS_SUFFIX -> AppProcessRole.CRASH
            processName == packageName + TOOLCHAIN_PROCESS_SUFFIX -> AppProcessRole.TOOLCHAIN
            CrashLogPrivacyClassifier.isUserRuntimeProcess(packageName, processName) -> AppProcessRole.USER_RUNTIME
            else -> AppProcessRole.OTHER
        }
    }
}
