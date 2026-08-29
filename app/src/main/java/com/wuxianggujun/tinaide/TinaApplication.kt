package com.wuxianggujun.tinaide

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.content.Context
import androidx.compose.ui.ComposeUiFlags
import androidx.compose.ui.ExperimentalComposeUiApi
import com.itsaky.androidide.treesitter.TreeSitter
import com.wuxianggujun.tinaide.core.compile.di.compileModule
import com.wuxianggujun.tinaide.core.config.IConfigManager
import com.wuxianggujun.tinaide.core.config.di.configModule
import com.wuxianggujun.tinaide.core.crash.CrashLogAutoUploader
import com.wuxianggujun.tinaide.core.crash.NativeCrashHandler
import com.wuxianggujun.tinaide.core.debug.di.debugModule
import com.wuxianggujun.tinaide.core.i18n.AppStrings
import com.wuxianggujun.tinaide.core.logging.LogProcessRegistry
import com.wuxianggujun.tinaide.core.logging.TinaTimber
import com.wuxianggujun.tinaide.core.proot.PRootBootstrap
import com.wuxianggujun.tinaide.core.proot.di.prootModule
import com.wuxianggujun.tinaide.core.util.CrashLogPrivacyClassifier
import com.wuxianggujun.tinaide.database.di.databaseModule
import com.wuxianggujun.tinaide.database.worker.FavoriteSyncWorker
import com.wuxianggujun.tinaide.di.appModule
import com.wuxianggujun.tinaide.di.appViewModelModule
import com.wuxianggujun.tinaide.editor.di.editorModule
import com.wuxianggujun.tinaide.extensions.applyTinaSystemBars
import com.wuxianggujun.tinaide.output.di.outputModule
import com.wuxianggujun.tinaide.plugin.PluginCapabilities
import com.wuxianggujun.tinaide.plugin.PluginManager
import com.wuxianggujun.tinaide.plugin.di.pluginModule
import com.wuxianggujun.tinaide.startup.BundledPackagesInstallTask
import com.wuxianggujun.tinaide.startup.AppProcessRole
import com.wuxianggujun.tinaide.startup.AppProcessRoleClassifier
import com.wuxianggujun.tinaide.startup.CoreServiceRegistrar
import com.wuxianggujun.tinaide.startup.ProjectMetadataInitializer
import com.wuxianggujun.tinaide.startup.ServerConfigSyncTask
import com.wuxianggujun.tinaide.startup.StartupFlowManager
import com.wuxianggujun.tinaide.startup.ThemeInitializer
import com.wuxianggujun.tinaide.storage.di.storageModule
import com.wuxianggujun.tinaide.terminal.di.terminalModule
import com.wuxianggujun.tinaide.ui.activity.CrashActivity
import com.wuxianggujun.tinaide.ui.compose.screens.help.di.helpModule
import com.wuxianggujun.tinaide.ui.compose.screens.packages.di.packagesModule
import com.wuxianggujun.tinaide.ui.compose.screens.settings.di.settingsModule
import com.wuxianggujun.tinaide.ui.workspace.di.workspaceModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.get as koinGet
import timber.log.Timber

class TinaApplication : Application() {

    companion object {
        private const val TAG = "TinaApplication"
    }

    // 应用级协程作用域（用于后台任务）
    // 使用 Dispatchers.Default 而非 Dispatchers.Main：后台任务（ServerConfigSync、Auth、BundledPackages 等）
    // 不需要在主线程排队，子任务按需通过 withContext(Dispatchers.IO) 切换到 IO 线程。
    private val applicationScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    /**
     * attachBaseContext 中初始化 xCrash
     *
     * xCrash 必须尽早初始化以捕获 Native 崩溃，
     * 在 attachBaseContext 中初始化可确保在任何其他代码执行前就绑定信号处理器。
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)

        // 初始化 xCrash（Native 崩溃捕获）
        // 必须在 attachBaseContext 中尽早调用
        val processName = Application.getProcessName()
        val processRole = AppProcessRoleClassifier.classify(packageName, processName)
        if (processRole == AppProcessRole.PLUGIN_RUNTIME) {
            // The isolated Lua process must not initialize xCrash, logging, storage or host services.
            return
        }
        // 主进程和原生运行容器进程弹出崩溃界面。
        // SDL 用户 native 程序运行在隔离进程，崩溃时只结束运行容器，并由 :crash 进程展示日志。
        // 用户项目/插件运行日志属于用户隐私，只本地保存与展示，不上传到 TinaIDE 服务器。
        val crashSource = when {
            CrashLogPrivacyClassifier.isHostAppProcess(packageName, processName) -> CrashActivity.SOURCE_APP
            CrashLogPrivacyClassifier.isUserRuntimeProcess(
                packageName,
                processName
            ) -> CrashActivity.SOURCE_USER_NATIVE
            else -> null
        }
        NativeCrashHandler.setCrashUploadEnabled(
            CrashLogPrivacyClassifier.shouldUploadCrashForProcess(packageName, processName)
        )
        NativeCrashHandler.setCrashDisplayer(
            if (crashSource != null) {
                NativeCrashHandler.CrashDisplayer { context, crashReport, crashLogFileName ->
                    CrashActivity.start(
                        context = context,
                        crashReport = crashReport,
                        source = crashSource,
                        crashLogFileName = crashLogFileName,
                    )
                }
            } else {
                null
            }
        )
        NativeCrashHandler.install(this)

        // 初始化 Timber 日志框架（尽早初始化以捕获所有日志）
        val logsRoot = com.wuxianggujun.tinaide.storage.ProjectPaths.getLogsRoot(this)
        TinaTimber.initialize(
            context = this,
            isDebug = BuildConfig.DEBUG,
            logDir = if (processRole == AppProcessRole.HOST) {
                com.wuxianggujun.tinaide.storage.ProjectPaths.ensureDir(logsRoot)
            } else {
                logsRoot
            },
            // xCrash tombstone 与 CrashActivity 展示链路不依赖 Timber 文件 Tree。
            // 仅宿主进程写共享文件，避免 :crash/native 多进程并发追加同一日志文件。
            enableFileLogging = processRole == AppProcessRole.HOST,
        )
        LogProcessRegistry.recordProcess(this, android.os.Process.myPid(), processName)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate() {
        super.onCreate()
        val processName = Application.getProcessName()
        val processRole = AppProcessRoleClassifier.classify(packageName, processName)
        if (processRole == AppProcessRole.TOOLCHAIN || processRole == AppProcessRole.PLUGIN_RUNTIME) {
            Timber.tag(TAG).i("Restricted process detected, skipping app initialization: %s", processRole)
            return
        }

        // Compose 1.11 keeps the legacy trackpad path behind this official flag.
        // Newer fake-finger reinterpretation breaks two-finger scrolling on some OriginOS tablets.
        ComposeUiFlags.isTrackpadGestureHandlingEnabled = false

        AppStrings.initialize(this)
        if (processRole != AppProcessRole.HOST) {
            // :crash 继续保留 xCrash 日志展示、国际化和主题；用户 runtime 也保留 Activity
            // 必需资源。数据库、Koin、Tree-sitter 与宿主后台任务只允许主进程初始化。
            val themeInitializer = ThemeInitializer(this)
            themeInitializer.initialize()
            themeInitializer.applyNightMode()
            Timber.tag(TAG).i(
                "Non-host process initialized with minimal runtime: role=%s process=%s",
                processRole,
                processName,
            )
            return
        }

        // 初始化项目元数据存储的 IDE 版本信息
        ProjectMetadataInitializer(BuildConfig.VERSION_NAME).execute()

        // 能执行到这里的只有 HOST 进程。
        runCatching {
            TreeSitter.loadLibrary()
            Timber.i("android-tree-sitter native library loaded successfully")
        }.onFailure { t ->
            Timber.e(t, "CRITICAL: Failed to load android-tree-sitter native library — tree-sitter features will crash")
        }

        // 清理旧的崩溃日志（保留最近 10 个）
        NativeCrashHandler.cleanupOldTombstones(10)

        // 初始化 Koin DI 框架
        startKoin {
            androidContext(this@TinaApplication)
            modules(
                configModule,
                storageModule,
                databaseModule,
                pluginModule,
                editorModule,
                outputModule,
                packagesModule,
                settingsModule,
                terminalModule,
                helpModule,
                workspaceModule,
                debugModule,
                prootModule,
                compileModule,
                appModule,
                appViewModelModule,
            )
        }

        // 执行副作用初始化（Prefs、设备信息、PRoot 等）
        CoreServiceRegistrar(this).execute()

        // 启动时立即尝试上传主进程崩溃日志，并注册 JobScheduler 兜底重试。
        CrashLogAutoUploader.uploadOnStartup(this, applicationScope)

        // 服务器配置同步不依赖本地 C/C++ 工具链，不应为了它在 Application 与 Portal
        // 重复扫描 sysroot/toolchain 资产。
        ServerConfigSyncTask(this, applicationScope).execute()

        // 调度收藏后台同步任务
        FavoriteSyncWorker.schedule(this)

        // 安装内置包（SDL3 等预编译库）
        BundledPackagesInstallTask(this, applicationScope).execute()

        // 初始化主题
        val themeInitializer = ThemeInitializer(this)
        themeInitializer.initialize()
        themeInitializer.applyNightMode()

        // 统一修复系统栏图标颜色：enableEdgeToEdge() 可能在部分 ROM/机型上覆写为错误模式
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {
                if (activity is CrashActivity) return
                activity.applyTinaSystemBars()
            }

            override fun onActivityResumed(activity: Activity) {
                if (activity is CrashActivity) return
                activity.applyTinaSystemBars()
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        // PRoot 运行载荷随 APK 内置，但功能启停只由插件 linuxEnvironment capability 决定。
        // 注意：这里只在 HOST 进程监听，避免多进程同时操作 rootfs。
        val pluginManager: PluginManager = koinGet(PluginManager::class.java)
        val startupFlowManager = StartupFlowManager(
            this,
            koinGet(IConfigManager::class.java),
        )
        applicationScope.launch(Dispatchers.IO) {
            pluginManager.enabledCapabilitiesFlow.collect { capabilities ->
                if (PluginCapabilities.LINUX_ENVIRONMENT !in capabilities) {
                    PRootBootstrap.cancel(
                        applicationContext = this@TinaApplication,
                        reason = "Linux environment plugin disabled",
                    )
                    Timber.tag(TAG).i("Skip PRoot bootstrap: linuxEnvironment capability disabled")
                    return@collect
                }

                if (startupFlowManager.isPRootEnvironmentReady()) {
                    PRootBootstrap.start(this@TinaApplication)
                } else {
                    Timber.tag(TAG).i("Skip PRoot bootstrap: environment not ready")
                }
            }
        }
    }

    override fun onTerminate() {
        super.onTerminate()

        // 关闭 Timber 日志系统
        TinaTimber.shutdown()
    }
}
