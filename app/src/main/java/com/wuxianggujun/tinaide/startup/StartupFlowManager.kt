package com.wuxianggujun.tinaide.startup

import android.content.Context
import android.content.Intent
import com.wuxianggujun.tinaide.core.config.ConfigKeys
import com.wuxianggujun.tinaide.core.config.IConfigManager
import com.wuxianggujun.tinaide.core.ndk.AndroidNativeToolchainManager
import com.wuxianggujun.tinaide.core.ndk.AndroidSysrootManager
import com.wuxianggujun.tinaide.core.proot.PRootBootstrap
import com.wuxianggujun.tinaide.core.proot.ToolchainConfig
import com.wuxianggujun.tinaide.ui.workspace.DependencyInstallActivity
import timber.log.Timber

/**
 * 启动流程管理器
 *
 * 职责：
 * - 检测首次启动 / 依赖是否就绪
 * - 决定跳转到引导页面还是直接进入主界面
 * - 单一职责：仅负责启动流程判断，不包含 UI 逻辑
 */
class StartupFlowManager(
    private val context: Context,
    private val configManager: IConfigManager
) {

    companion object {
        private const val TAG = "StartupFlowManager"
    }

    /**
     * 检查启动流程
     *
     * @return Intent 如果需要跳转到引导页面，返回对应的 Intent；否则返回 null 表示可以直接进入主界面
     */
    fun checkStartupFlow(): Intent? {
        Timber.tag(TAG).d("Checking startup flow...")
        return runCatching {
            if (requiresDependencyInstallation()) {
                // 依赖未就绪：进入解压安装页（默认内置 Clang 资产）
                Timber.tag(TAG).i("Dependency installation required, redirecting to installer")
                DependencyInstallActivity.createIntent(
                    context = context,
                    config = ToolchainConfig.recommended(),
                    installLinuxEnvironment = false
                )
            } else {
                Timber.tag(TAG).d("Startup checks passed, entering main interface")
                null
            }
        }.onFailure { error ->
            Timber.tag(TAG).e(error, "Startup check failed; redirecting to dependency installer")
        }.getOrElse {
            // 读取配置或资产状态异常时 fail-safe，避免停留在 Splash 或进入半初始化工作区。
            DependencyInstallActivity.createIntent(
                context = context,
                config = ToolchainConfig.recommended(),
                installLinuxEnvironment = false,
            )
        }
    }

    /**
     * 是否需要先进入依赖安装流程
     *
     * 判定条件：
     * 1) 配置未完成；
     * 2) 配置已完成但运行资产缺失（常见于重装后恢复了 SharedPreferences，filesDir 未恢复）。
     */
    fun requiresDependencyInstallation(): Boolean {
        val setupCompleted = configManager.get(ConfigKeys.WorkspaceSetupCompleted)
        if (!setupCompleted) {
            Timber.tag(TAG).d("Workspace setup not completed")
            return true
        }

        val runtimeReady = isBuiltinRuntimeReady()
        if (!runtimeReady) {
            Timber.tag(TAG).w(
                "Workspace setup flag is true but runtime assets are missing, fallback to dependency installer"
            )
            // 自愈：避免后续流程继续信任已失效的 setup 标记
            configManager.set(ConfigKeys.WorkspaceSetupCompleted, false)
            return true
        }

        return false
    }

    /**
     * 是否为首次启动
     */
    fun isFirstLaunch(): Boolean = !configManager.get(ConfigKeys.WorkspaceSetupCompleted)

    /**
     * 检查 PRoot 环境是否已就绪
     *
     * 用于 Application.onCreate() 中判断是否需要启动后台 PRoot bootstrap。
     * 不再自动修复 WorkspaceSetupCompleted 标记，避免误判。
     *
     * @return true 如果 PRoot 环境已就绪（可以启动后台 bootstrap）
     */
    fun isPRootEnvironmentReady(): Boolean = PRootBootstrap.isEnvironmentReady(context.applicationContext)

    private fun isBuiltinRuntimeReady(): Boolean = runCatching {
        val appContext = context.applicationContext
        val sysrootReady = AndroidSysrootManager(appContext).isInstalled()
        val toolchainReady = AndroidNativeToolchainManager(appContext).isReadyForCurrentAssets()
        Timber.tag(TAG).d(
            "Builtin runtime ready check: sysroot=%s, currentToolchain=%s",
            sysrootReady,
            toolchainReady
        )
        sysrootReady && toolchainReady
    }.onFailure { t ->
        Timber.tag(TAG).w(t, "Failed to check builtin runtime readiness")
    }.getOrDefault(false)
}
