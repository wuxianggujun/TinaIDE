package com.wuxianggujun.tinaide.ui.sdl

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.wuxianggujun.tinaide.MainActivity
import com.wuxianggujun.tinaide.core.compile.SdlOrientation
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.logging.LogProcessRegistry
import com.wuxianggujun.tinaide.ui.compose.components.FloatingOverlay
import com.wuxianggujun.tinaide.ui.runtime.NativeLaunchEnvironment
import com.wuxianggujun.tinaide.ui.runtime.NativeStdStreamRedirect
import com.wuxianggujun.tinaide.ui.theme.TinaIDETheme
import java.io.File
import org.libsdl.app.SDLActivity
import timber.log.Timber

internal fun buildSdlLibraryLoadOrder(
    preSdlLibraryPaths: List<String>,
    sdlLibraryPath: String,
    preloadLibraryPaths: List<String>,
    mainLibraryPath: String
): List<String> = linkedSetOf<String>().apply {
    preSdlLibraryPaths.filterTo(this) { it.isNotBlank() }
    sdlLibraryPath.takeIf { it.isNotBlank() }?.let(::add)
    preloadLibraryPaths.filterTo(this) { it.isNotBlank() }
    mainLibraryPath.takeIf { it.isNotBlank() }?.let(::add)
}.toList()

/**
 * 外部 SDL 启动 Activity：
 * - 不使用 APK 内置 SDL so；
 * - 通过绝对路径加载包机制下载的 SDL2/SDL3 共享库；
 * - 使用用户编译产物 .so 作为 SDL_main 所在主库。
 */
class ExternalSdlActivity :
    SDLActivity(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    // -- Lifecycle infrastructure (SDLActivity extends plain Activity, not ComponentActivity) --
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val viewModelStoreField = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = viewModelStoreField

    companion object {
        private const val TAG = "ExternalSdlActivity"
        private const val DOUBLE_BACK_EXIT_INTERVAL_MS = 2000L
        private const val NATIVE_QUIT_GRACE_PERIOD_MS = 800L
        private const val SDL_RENDER_DRIVER_ENV = "SDL_RENDER_DRIVER"
        private const val DEFAULT_ANDROID_SDL_RENDER_DRIVER = "opengles2"

        const val EXTRA_SDL_LIBRARY_PATH = "extra_sdl_library_path"
        const val EXTRA_MAIN_LIBRARY_PATH = "extra_main_library_path"
        const val EXTRA_REQUIRED_SDL_MAJOR = "extra_required_sdl_major"
        const val EXTRA_PRE_SDL_LIBRARY_PATHS = "extra_pre_sdl_library_paths"
        const val EXTRA_PRELOAD_LIBRARY_PATHS = "extra_preload_library_paths"
        const val EXTRA_SDL_ORIENTATION = "extra_sdl_orientation"
        const val EXTRA_ENABLE_FLOATING_LOG = "extra_enable_floating_log"

        fun createIntent(
            context: Context,
            sdlLibraryPath: String,
            mainLibraryPath: String,
            requiredSdlMajor: Int,
            preSdlLibraryPaths: List<String>,
            preloadLibraryPaths: List<String>,
            sdlOrientation: SdlOrientation = SdlOrientation.AUTO,
            enableFloatingLog: Boolean = false,
            launchEnvironment: Map<String, String> = emptyMap(),
        ): Intent = Intent(context, ExternalSdlActivity::class.java).apply {
            putExtra(EXTRA_SDL_LIBRARY_PATH, sdlLibraryPath)
            putExtra(EXTRA_MAIN_LIBRARY_PATH, mainLibraryPath)
            putExtra(EXTRA_REQUIRED_SDL_MAJOR, requiredSdlMajor)
            putStringArrayListExtra(
                EXTRA_PRE_SDL_LIBRARY_PATHS,
                ArrayList(preSdlLibraryPaths)
            )
            putStringArrayListExtra(
                EXTRA_PRELOAD_LIBRARY_PATHS,
                ArrayList(preloadLibraryPaths)
            )
            putExtra(EXTRA_SDL_ORIENTATION, sdlOrientation.name)
            putExtra(EXTRA_ENABLE_FLOATING_LOG, enableFloatingLog)
            NativeLaunchEnvironment.putIntoIntent(
                this,
                withAndroidSdlDefaults(launchEnvironment)
            )
        }

        private fun withAndroidSdlDefaults(environment: Map<String, String>): Map<String, String> {
            if (environment.containsKey(SDL_RENDER_DRIVER_ENV)) return environment

            return buildMap(environment.size + 1) {
                // Android Vulkan renderer 在部分驱动的 Surface 恢复路径上不稳定，默认使用 OpenGL ES 保留后台恢复体验。
                put(SDL_RENDER_DRIVER_ENV, DEFAULT_ANDROID_SDL_RENDER_DRIVER)
                putAll(environment)
            }
        }
    }

    private var sdlLibraryPath: String = ""
    private var mainLibraryPath: String = ""
    private var requiredSdlMajor: Int = 0
    private var preSdlLibraryPaths: List<String> = emptyList()
    private var preloadLibraryPaths: List<String> = emptyList()

    private var userOrientation: SdlOrientation = SdlOrientation.AUTO
    private var enableFloatingLog: Boolean = false
    private var launchEnvironmentOwnerId: String? = null
    private var lastBackPressTime: Long = 0L
    private val finishHandler = Handler(Looper.getMainLooper())
    private val forceFinishAfterQuitTimeout = Runnable {
        if (isFinishing || isDestroyed) return@Runnable
        Timber.tag(TAG).w(
            "SDL thread did not exit within %d ms after quit request, forcing finish",
            NATIVE_QUIT_GRACE_PERIOD_MS
        )
        finish()
    }

    @Volatile private var returnToParentOnFinish = false

    @Volatile private var nativeShutdownRequested = false

    @Volatile private var parentNavigationStarted = false

    // region Lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        LogProcessRegistry.recordCurrentProcess(this)

        // Must restore before super.onCreate (while lifecycle is INITIALIZED)
        savedStateRegistryController.performRestore(savedInstanceState)

        sdlLibraryPath = intent.getStringExtra(EXTRA_SDL_LIBRARY_PATH).orEmpty()
        mainLibraryPath = intent.getStringExtra(EXTRA_MAIN_LIBRARY_PATH).orEmpty()
        requiredSdlMajor = intent.getIntExtra(EXTRA_REQUIRED_SDL_MAJOR, 0)
        preSdlLibraryPaths = intent.getStringArrayListExtra(EXTRA_PRE_SDL_LIBRARY_PATHS)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            .orEmpty()
        preloadLibraryPaths = intent.getStringArrayListExtra(EXTRA_PRELOAD_LIBRARY_PATHS)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            .orEmpty()

        val orientationName = intent.getStringExtra(EXTRA_SDL_ORIENTATION)
        userOrientation = orientationName?.let {
            runCatching { SdlOrientation.valueOf(it) }.getOrDefault(SdlOrientation.AUTO)
        } ?: SdlOrientation.AUTO
        enableFloatingLog = intent.getBooleanExtra(EXTRA_ENABLE_FLOATING_LOG, false)

        val validationError = validateLaunchParams()
        if (validationError != null) {
            Toast.makeText(this, validationError, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        launchEnvironmentOwnerId = "${javaClass.simpleName}@${System.identityHashCode(this)}"
        NativeLaunchEnvironment.apply(
            ownerId = launchEnvironmentOwnerId!!,
            environment = NativeLaunchEnvironment.readFromIntent(intent),
        )
        // 运行时成功启动后，任何正常 finish 都应回到 MainActivity，而不是回到启动器。
        returnToParentOnFinish = true
        applySdlOrientation()
        NativeStdStreamRedirect.start()

        super.onCreate(savedInstanceState)

        // SDLActivity extends plain Activity, not ComponentActivity.
        // ComposeView requires ViewTree owners — set them on decorView so the
        // entire view hierarchy (including mLayout and its children) can find them.
        window.decorView.let { decorView ->
            decorView.setViewTreeLifecycleOwner(this)
            decorView.setViewTreeViewModelStoreOwner(this)
            decorView.setViewTreeSavedStateRegistryOwner(this)
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        addFloatingOverlay()
    }

    override fun onStart() {
        super.onStart()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    override fun onResume() {
        super.onResume()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onPause() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        super.onPause()
    }

    override fun onStop() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onStop()
    }

    override fun onDestroy() {
        finishHandler.removeCallbacks(forceFinishAfterQuitTimeout)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStoreField.clear()
        super.onDestroy()
        NativeStdStreamRedirect.stop()
        launchEnvironmentOwnerId?.let(NativeLaunchEnvironment::clear)
        launchEnvironmentOwnerId = null
    }

    override fun finish() {
        finishHandler.removeCallbacks(forceFinishAfterQuitTimeout)
        // 正常场景下 SDLActivity 是从 MainActivity 之上启动的，直接 finish 即可自然回退。
        // 只有当 SDLActivity 意外成为当前 task 根节点时，才补拉 MainActivity，避免直接落回启动器。
        if (returnToParentOnFinish && !parentNavigationStarted && isTaskRoot) {
            parentNavigationStarted = true
            navigateBackToParent()
        }
        super.finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        savedStateRegistryController.performSave(outState)
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

    override fun getExpectedSdlVersion(): String = if (requiredSdlMajor == 2 || requiredSdlMajor == 3) {
        "$requiredSdlMajor.x"
    } else {
        super.getExpectedSdlVersion()
    }

    override fun isNativeVersionCompatible(version: String): Boolean {
        val nativeMajor = version.substringBefore('.').toIntOrNull() ?: return false
        return nativeMajor == requiredSdlMajor
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
            returnToParentAfterFinish = true
        )
    }

    private fun requestNativeShutdown(
        reason: String,
        returnToParentAfterFinish: Boolean,
    ) {
        if (isFinishing) return

        val sdlThread = mSDLThread
        if (sdlThread == null || !sdlThread.isAlive) {
            returnToParentOnFinish = returnToParentAfterFinish
            finish()
            return
        }

        if (nativeShutdownRequested) return
        nativeShutdownRequested = true
        returnToParentOnFinish = returnToParentAfterFinish

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

    /**
     * 仅在 SDLActivity 已经成为 task 根节点时，兜底拉起 MainActivity。
     */
    private fun navigateBackToParent() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to navigate back to parent")
        }
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

    /**
     * 在 SDL Surface 之上叠加 Compose 悬浮层（退出球 + 可选日志面板）。
     *
     * SDL 的 [mLayout] 是一个 RelativeLayout，这里在其上层添加一个透明 ComposeView。
     * Compose 层仅拦截小球/面板区域的触摸事件，其余区域透传给 SDL Surface。
     */
    private fun addFloatingOverlay() {
        val composeView = ComposeView(this).apply {
            setContent {
                TinaIDETheme {
                    FloatingOverlay(
                        enableFloatingLog = enableFloatingLog,
                        onExit = { exitToParent() }
                    )
                }
            }
        }
        val overlayParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        mLayout?.addView(composeView, overlayParams)
            ?: Timber.tag(TAG).w("mLayout is null, cannot add floating overlay")
    }

    private fun validateLaunchParams(): String? {
        if (sdlLibraryPath.isBlank() || mainLibraryPath.isBlank()) {
            return Strings.sdl_runtime_error_missing_launch_params.strOr(this)
        }

        if (requiredSdlMajor != 2 && requiredSdlMajor != 3) {
            return Strings.sdl_runtime_error_invalid_required_major.strOr(this, requiredSdlMajor)
        }

        return null
    }

    // endregion
}
