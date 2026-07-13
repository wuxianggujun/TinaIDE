package com.wuxianggujun.tinaide.startup

import android.content.Context
import com.wuxianggujun.tinaide.core.packages.BundledPackagesInstaller
import com.wuxianggujun.tinaide.core.packages.BundledPackagesReadiness
import com.wuxianggujun.tinaide.core.packages.store.LocalInstallStateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 内置包安装启动任务
 *
 * 在应用启动时自动解压 assets 中的预编译库
 */
class BundledPackagesInstallTask(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "BundledPackagesInstallTask"
    }

    fun execute() {
        // 在调度协程前同步发布状态，避免 UI 已可点击但安装协程尚未开始的窗口。
        BundledPackagesReadiness.markInstalling()
        scope.launch {
            try {
                Timber.tag(TAG).d("Starting bundled packages installation...")

                val installer = BundledPackagesInstaller(
                    context = context,
                    installStateStore = LocalInstallStateStore(context)
                )

                installer.installBundledPackages()

                BundledPackagesReadiness.markReady()
                Timber.tag(TAG).i("Bundled packages installation completed")
            } catch (cancelled: CancellationException) {
                BundledPackagesReadiness.markFailed()
                throw cancelled
            } catch (e: Exception) {
                BundledPackagesReadiness.markFailed()
                Timber.tag(TAG).e(e, "Failed to install bundled packages")
            }
        }
    }
}
