package com.wuxianggujun.tinaide.ui.sdl

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.Toast
import com.wuxianggujun.tinaide.core.compile.SdlOrientation
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.ui.runtime.GraphicalRuntimeActivityHost
import com.wuxianggujun.tinaide.ui.runtime.GraphicalRuntimeIntentOptions
import org.libsdl2.app.SDLActivity
import timber.log.Timber

/**
 * 外部 SDL2 启动 Activity：
 * - 不使用 APK 内置 SDL so；
 * - 通过绝对路径加载包机制下载的 SDL2 共享库；
 * - 使用用户编译产物 .so 作为 SDL_main 所在主库。
 *
 * SDL2 和 SDL3 的 Java/JNI glue 不是同一套 ABI，因此不能共用 SDL3 的
 * [org.libsdl.app.SDLActivity]。本 Activity 只承载 SDL2，并在独立进程中运行。
 */
class ExternalSdl2Activity : SDLActivity() {

    companion object {
        private const val TAG = "ExternalSdl2Activity"
        private const val DOUBLE_BACK_EXIT_INTERVAL_MS = 2000L
        private const val NATIVE_QUIT_GRACE_PERIOD_MS = 800L
    }

    private var sdlLibraryPath: String = ""
    private var mainLibraryPath: String = ""
    private var requiredSdlMajor: Int = 0
    private var preSdlLibraryPaths: List<String> = emptyList()
    private var preloadLibraryPaths: List<String> = emptyList()

    private var userOrientation: SdlOrientation = SdlOrientation.AUTO
    private var enableFloatingLog: Boolean = false
    private var lastBackPressTime: Long = 0L
    private val runtimeHost = GraphicalRuntimeActivityHost(this, TAG)
    private val finishHandler = Handler(Looper.getMainLooper())
    private val forceFinishAfterQuitTimeout = Runnable {
        if (isFinishing || isDestroyed) return@Runnable
        Timber.tag(TAG).w(
            "SDL thread did not exit within %d ms after quit request, forcing finish",
            NATIVE_QUIT_GRACE_PERIOD_MS
        )
        finish()
    }

    @Volatile private var nativeShutdownRequested = false

    // region Lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        sdlLibraryPath = intent.getStringExtra(ExternalSdlActivity.EXTRA_SDL_LIBRARY_PATH).orEmpty()
        mainLibraryPath = intent.getStringExtra(ExternalSdlActivity.EXTRA_MAIN_LIBRARY_PATH).orEmpty()
        requiredSdlMajor = intent.getIntExtra(ExternalSdlActivity.EXTRA_REQUIRED_SDL_MAJOR, 0)
        preSdlLibraryPaths = intent.getStringArrayListExtra(ExternalSdlActivity.EXTRA_PRE_SDL_LIBRARY_PATHS)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            .orEmpty()
        preloadLibraryPaths = intent.getStringArrayListExtra(ExternalSdlActivity.EXTRA_PRELOAD_LIBRARY_PATHS)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            .orEmpty()

        val orientationName = intent.getStringExtra(ExternalSdlActivity.EXTRA_SDL_ORIENTATION)
        userOrientation = orientationName?.let {
            runCatching { SdlOrientation.valueOf(it) }.getOrDefault(SdlOrientation.AUTO)
        } ?: SdlOrientation.AUTO
        enableFloatingLog = GraphicalRuntimeIntentOptions.readFloatingLogEnabled(intent)

        val validationError = validateLaunchParams()
        if (validationError != null) {
            Toast.makeText(this, validationError, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        applySdlOrientation()
        runtimeHost.onPreCreate(savedInstanceState, intent)
        runtimeHost.requestReturnToParentOnFinish()

        super.onCreate(savedInstanceState)
        attachRuntimeOverlay()
    }

    override fun onStart() {
        super.onStart()
        runtimeHost.onStart()
    }

    override fun onResume() {
        super.onResume()
        runtimeHost.onResume()
    }

    override fun onPause() {
        runtimeHost.onPause()
        super.onPause()
    }

    override fun onStop() {
        runtimeHost.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        val shouldTerminateRuntimeProcess = isFinishing && !isChangingConfigurations
        finishHandler.removeCallbacks(forceFinishAfterQuitTimeout)
        try {
            super.onDestroy()
        } finally {
            try {
                runtimeHost.onDestroy()
            } finally {
                // System.load keeps user libraries resident; each run needs a fresh process.
                if (shouldTerminateRuntimeProcess) Process.killProcess(Process.myPid())
            }
        }
    }

    override fun finish() {
        finishHandler.removeCallbacks(forceFinishAfterQuitTimeout)
        runtimeHost.onFinish()
        super.finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        runtimeHost.onSaveInstanceState(outState)
    }

    // endregion

    // region SDL overrides

    /**
     * 拦截 SDL 原生层对方向的覆盖。
     *
     * SDL 初始化窗口后会通过 JNI 调用 [SDLActivity.setOrientation] -> [setOrientationBis]，
     * 用窗口宽高推算方向并调用 [setRequestedOrientation]。如果用户在运行配置中指定了方向，
     * 这里直接忽略 SDL 的请求，保留用户的选择。
     */
    override fun setOrientationBis(w: Int, h: Int, resizable: Boolean, hint: String?) {
        if (userOrientation != SdlOrientation.AUTO) {
            Timber.tag(TAG).d(
                "Ignoring SDL setOrientationBis(w=%d, h=%d) — user forced %s",
                w,
                h,
                userOrientation
            )
            return
        }
        super.setOrientationBis(w, h, resizable, hint)
    }

    /**
     * 返回键处理策略：
     *
     * - SDL 未 trap 返回键时：正常退出
     * - SDL trap 了返回键时（`SDL_ANDROID_TRAP_BACK_BUTTON=true`，常见于游戏）：
     *   第一次按返回键显示 Toast 提示，2 秒内再按一次强制退出。
     *   悬浮球的退出按钮始终可用作备选退出方式。
     */
    override fun handleBackPressed() {
        val trapBack = nativeGetHintBoolean("SDL_ANDROID_TRAP_BACK_BUTTON", false)
        if (!trapBack) {
            exitToParent()
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastBackPressTime < DOUBLE_BACK_EXIT_INTERVAL_MS) {
            exitToParent()
        } else {
            lastBackPressTime = now
            Toast.makeText(
                this,
                Strings.floating_overlay_double_back_hint.strOr(this),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun loadLibraries() {
        val loadedPaths = linkedSetOf<String>()

        @SuppressLint("UnsafeDynamicallyLoadedCode")
        fun loadAbsolutePath(path: String) {
            if (path.isBlank() || !loadedPaths.add(path)) return
            Timber.tag(TAG).d("System.load: %s", path)
            System.load(path)
        }

        buildSdlLibraryLoadOrder(
            preSdlLibraryPaths = preSdlLibraryPaths,
            sdlLibraryPath = sdlLibraryPath,
            preloadLibraryPaths = preloadLibraryPaths,
            mainLibraryPath = mainLibraryPath
        ).forEach(::loadAbsolutePath)
    }

    override fun getBrokenLibrariesErrorTitle(): String = Strings.sdl_host_title.strOr(this)

    override fun getBrokenLibrariesErrorMessage(errorMessage: String?): String =
        Strings.sdl_runtime_error_load_failed.strOr(this, errorMessage.orEmpty())

    override fun getBrokenLibrariesExitButtonText(): String =
        Strings.floating_overlay_exit.strOr(this)

    override fun getLibraries(): Array<String> = emptyArray()

    override fun getMainSharedObject(): String = mainLibraryPath

    override fun getExpectedSdlVersion(): String = "2.x"

    override fun isNativeVersionCompatible(version: String): Boolean {
        val nativeMajor = version.substringBefore('.').toIntOrNull() ?: return false
        return nativeMajor == 2
    }

    // endregion

    // region Private helpers

    /**
     * 安全退出：
     *
     * 先请求 SDL 线程自行处理 Quit 并完成 renderer/window 销毁，等它真正 finish 时再回到父页面。
     * 这样可以避免 `surfaceDestroyed -> SDL_DestroyRenderer` 的 Android Surface 生命周期竞态。
     */
    private fun exitToParent() {
        requestNativeShutdown(
            reason = "returning to parent",
        )
    }

    private fun requestNativeShutdown(
        reason: String,
    ) {
        if (isFinishing) return

        val sdlThread = mSDLThread
        if (sdlThread == null || !sdlThread.isAlive) {
            finish()
            return
        }

        if (nativeShutdownRequested) return
        nativeShutdownRequested = true
        val quitRequested = runCatching {
            Timber.tag(TAG).i("Requesting SDL quit before %s", reason)
            nativeSendQuit()
        }.onFailure { throwable ->
            Timber.tag(TAG).w(throwable, "Failed to send SDL quit event before %s", reason)
        }.isSuccess

        if (!quitRequested) {
            finish()
            return
        }

        finishHandler.removeCallbacks(forceFinishAfterQuitTimeout)
        finishHandler.postDelayed(
            forceFinishAfterQuitTimeout,
            NATIVE_QUIT_GRACE_PERIOD_MS
        )
    }

    private fun applySdlOrientation() {
        requestedOrientation = when (userOrientation) {
            SdlOrientation.AUTO -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
            SdlOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            SdlOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        Timber.tag(TAG).d(
            "SDL orientation: %s -> requestedOrientation=%d",
            userOrientation,
            requestedOrientation
        )
    }

    private fun attachRuntimeOverlay() {
        val container = mLayout ?: findViewById<ViewGroup>(android.R.id.content)
        if (container == null) {
            Timber.tag(TAG).e("No SDL2 view container is available for runtime controls")
            return
        }
        runtimeHost.attachOverlay(
            container = container,
            enableFloatingLog = enableFloatingLog,
            onExit = ::exitToParent,
        )
    }

    private fun validateLaunchParams(): String? {
        if (sdlLibraryPath.isBlank() || mainLibraryPath.isBlank()) {
            return Strings.sdl_runtime_error_missing_launch_params.strOr(this)
        }

        if (requiredSdlMajor != 2) {
            return Strings.sdl_runtime_error_invalid_required_major.strOr(this, requiredSdlMajor)
        }

        return null
    }

    // endregion
}
