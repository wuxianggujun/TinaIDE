package com.wuxianggujun.tinaide.core.linuxdesktop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.system.Os
import com.termux.x11.CmdEntryPoint
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import java.io.File
import timber.log.Timber

/**
 * X server 宿主，运行在 `:x11` 进程（见模块 AndroidManifest）。
 *
 * 为什么是独立进程：`lorie/src/main/cpp/lorie/cmdentrypoint.cpp` 把 libc 的
 * `exit()` / `abort()` 覆盖成 `_exit()`，而 X server 线程执行
 * `exit(dix_main(argc, argv, envp))`。X server 任何一次 `FatalError()` 都会直接终止
 * 所在进程，不走 JVM 关闭流程、不抛异常、不可捕获——所以它不能待在主进程里。
 *
 * 为什么是前台 Service 而不是 Activity：X server 的生命周期要长于桌面窗口。
 * 用户关掉桌面 UI 后 guest 里的 XFCE 会话应当存活，下次打开窗口重新连上即可。
 *
 * 这里刻意**不做**任何异常兜底：X server 的致命错误走 `_exit()`，兜不住也不该兜。
 * 进程消失由主进程侧的 binder death 感知。
 */
class X11ServerService : Service() {

    private val controller = object : IX11ServerController.Stub() {
        override fun startServer(
            displayNumber: Int,
            argv: Array<String>,
            hostTmpDir: String,
            xkbConfigRoot: String,
        ): String? = synchronized(this@X11ServerService) {
            startServerLocked(displayNumber, argv, hostTmpDir, xkbConfigRoot)
        }

        override fun isServerRunning(): Boolean = serverStarted

        override fun getDisplayNumber(): Int = activeDisplayNumber
    }

    /**
     * X server 一旦在本进程启动就无法停止：`dix_main()` 跑在自己的线程上，退出路径是
     * `_exit()`。所以"停止 X server"只能靠结束整个 `:x11` 进程。此处只记录是否已启动，
     * 不提供 stop。
     */
    private var serverStarted: Boolean = false
    private var activeDisplayNumber: Int = NO_DISPLAY

    override fun onCreate() {
        super.onCreate()
        Timber.tag(TAG).i("X11ServerService created in pid=%d", android.os.Process.myPid())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        // 粘性重启会用 null intent 重新拉起，但 X server 的启动参数在 intent 里没有，
        // 且已死的 server 无法在同一进程重建，所以不要求系统重启本服务。
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = controller

    override fun onDestroy() {
        Timber.tag(TAG).i("X11ServerService destroyed")
        super.onDestroy()
    }

    private fun startServerLocked(
        displayNumber: Int,
        argv: Array<String>,
        hostTmpDir: String,
        xkbConfigRoot: String,
    ): String? {
        if (serverStarted) {
            return if (activeDisplayNumber == displayNumber) {
                Timber.tag(TAG).i("X server already running on :%d", displayNumber)
                null
            } else {
                // 同一进程里 dix_main 只能跑一次，换 display 必须换进程。
                "X server is already running on display :$activeDisplayNumber"
            }
        }

        val tmpDir = File(hostTmpDir)
        if (!tmpDir.isDirectory) {
            return "TMPDIR does not exist: $hostTmpDir"
        }
        if (!File(xkbConfigRoot).isDirectory) {
            return "XKB_CONFIG_ROOT does not exist: $xkbConfigRoot"
        }

        // CmdEntryPoint.start() 只从 getenv() 读这两个值——上游靠 termux-x11 shell 脚本
        // 预设，APK 内启动必须自己设。TMPDIR 决定 socket 落点
        // ($TMPDIR/.X11-unix/X<n>)，XKB_CONFIG_ROOT 显式指定可以跳过 lorie 基于
        // dirname($TMPDIR) 的隐式探测。
        return try {
            Os.setenv(ENV_TMPDIR, hostTmpDir, true)
            Os.setenv(ENV_XKB_CONFIG_ROOT, xkbConfigRoot, true)
            Timber.tag(TAG).i(
                "Starting X server on :%d with TMPDIR=%s XKB_CONFIG_ROOT=%s argv=%s",
                displayNumber, hostTmpDir, xkbConfigRoot, argv.joinToString(" "),
            )

            // 走 startEmbedded 而非 CmdEntryPoint.main()：后者是为 app_process 场景准备的，
            // 会反射 ActivityThread 伪造 Context 并按绝对路径 dlopen libXlorie，最后
            // Looper.loop() 永不返回。startEmbedded 自己 System.loadLibrary("Xlorie")，
            // 因为本 Service 先于任何 LorieView 启动，JNI_OnLoad 还没跑过。
            if (!CmdEntryPoint.startEmbedded(applicationContext, argv)) {
                return "X server failed to start; check logcat for the Xlorie error"
            }
            serverStarted = true
            activeDisplayNumber = displayNumber
            null
        } catch (error: Throwable) {
            // native start() 失败时上游是 System.exit(1)，而 exit() 已被覆盖成 _exit()，
            // 所以这里通常等不到异常——进程会直接消失。能走到这里说明是 JVM 侧的问题
            // （类加载、Os.setenv 权限等），值得如实报出来。
            Timber.tag(TAG).e(error, "Failed to start X server")
            "Failed to start X server: ${error.message ?: error::class.java.simpleName}"
        }
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    Strings.linux_desktop_notification_channel.strOr(this),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }

        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(Strings.linux_desktop_notification_title.strOr(this))
            .setContentText(Strings.linux_desktop_notification_text.strOr(this))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "X11ServerService"
        private const val CHANNEL_ID = "tinaide.linux.desktop"
        private const val NOTIFICATION_ID = 7893
        private const val ENV_TMPDIR = "TMPDIR"
        private const val ENV_XKB_CONFIG_ROOT = "XKB_CONFIG_ROOT"

        internal const val NO_DISPLAY = -1
    }
}
