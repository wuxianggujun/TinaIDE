package com.wuxianggujun.tinaide.core

import android.content.Context

/**
 * 应用级导航接口
 *
 * 供 feature 模块调用，避免直接依赖 app 模块中的 Activity 类。
 * 实现在 app 模块的 Koin `appModule` 中注册。
 */
interface IAppNavigator {
    /** 导航到项目管理器（清空返回栈） */
    fun navigateToProjectManager(context: Context)

    /** 打开终端 */
    fun navigateToTerminal(context: Context, workDir: String)

    /**
     * 打开 Linux 图形桌面窗口。
     *
     * 实现必须启动 `com.termux.x11.MainActivity`；feature 模块不得直接依赖该类。
     * X server 应已由调用方启动——本方法只负责打开渲染/输入窗口。
     */
    fun openLinuxDesktop(context: Context)
}
