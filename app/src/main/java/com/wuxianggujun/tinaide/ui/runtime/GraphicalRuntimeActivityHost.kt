package com.wuxianggujun.tinaide.ui.runtime

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
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
import com.wuxianggujun.tinaide.core.logging.LogProcessRegistry
import com.wuxianggujun.tinaide.ui.compose.components.GraphicalRuntimeOverlay
import com.wuxianggujun.tinaide.ui.theme.TinaIDETheme
import timber.log.Timber

/** Common Android UI/lifecycle shell shared by graphical runtime activities. */
internal class GraphicalRuntimeActivityHost(
    private val activity: Activity,
    private val logTag: String,
    private val exitGuard: GraphicalRuntimeExitGuard = GraphicalRuntimeExitGuard(
        terminateProcess = { GraphicalRuntimeProcessController.terminateCurrent(activity) },
    ),
) {
    private val finishHandler = Handler(Looper.getMainLooper())
    private val finishAfterQuit = Runnable {
        if (!activity.isFinishing && !activity.isDestroyed) activity.finish()
    }
    private var quitRequested = false
    private var launchRejected = false
    private val viewTreeOwner = GraphicalRuntimeViewTreeOwner()
    private var environmentOwnerId: String? = null
    private var prepared = false
    private var overlayAttached = false
    private var overlayView: ComposeView? = null
    private var backCallback: OnBackInvokedCallback? = null

    @Volatile
    private var returnToParentOnFinish = false

    @Volatile
    private var parentNavigationStarted = false

    /** Must run before Activity.super.onCreate while the overlay lifecycle is INITIALIZED. */
    fun onPreCreate(
        savedInstanceState: Bundle?,
        launchIntent: Intent,
    ): Boolean {
        check(!prepared) { "Graphical runtime host was prepared more than once" }
        if (!GraphicalRuntimeLaunchSignal.claim(launchIntent)) {
            launchRejected = true
            Timber.tag(logTag).i("Ignoring cancelled or duplicate graphical launch")
            // Do not arm the process guard: a newer Activity may own this process.
            activity.finish()
            return false
        }
        viewTreeOwner.performRestore(savedInstanceState)
        LogProcessRegistry.recordCurrentProcess(activity)

        environmentOwnerId =
            "${activity.javaClass.simpleName}@${System.identityHashCode(activity)}"
        NativeLaunchEnvironment.apply(
            ownerId = environmentOwnerId!!,
            environment = NativeLaunchEnvironment.readFromIntent(launchIntent),
        )
        NativeStdStreamRedirect.start()
        prepared = true
        return true
    }

    /** Attaches the shared floating return control above the runtime's rendering surface. */
    fun attachOverlay(
        container: ViewGroup,
        enableFloatingLog: Boolean,
        onExit: () -> Unit,
        onBack: () -> Unit = onExit,
    ) {
        check(prepared) { "Graphical runtime host must be prepared before attaching its overlay" }
        check(!overlayAttached) { "Graphical runtime overlay was attached more than once" }

        activity.window.decorView.let { decorView ->
            decorView.setViewTreeLifecycleOwner(viewTreeOwner)
            decorView.setViewTreeViewModelStoreOwner(viewTreeOwner)
            decorView.setViewTreeSavedStateRegistryOwner(viewTreeOwner)
        }
        viewTreeOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        val composeView = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                TinaIDETheme {
                    GraphicalRuntimeOverlay(
                        enableFloatingLog = enableFloatingLog,
                        onExit = onExit,
                    )
                }
            }
        }
        container.addView(
            composeView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        overlayView = composeView
        overlayAttached = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val callback = OnBackInvokedCallback { onBack() }
            activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback,
            )
            backCallback = callback
        }
    }

    fun onStart() = dispatchOverlayEvent(Lifecycle.Event.ON_START)

    fun onResume() = dispatchOverlayEvent(Lifecycle.Event.ON_RESUME)

    fun onPause() = dispatchOverlayEvent(Lifecycle.Event.ON_PAUSE)

    fun onStop() = dispatchOverlayEvent(Lifecycle.Event.ON_STOP)

    fun onSaveInstanceState(outState: Bundle) {
        if (prepared) viewTreeOwner.performSave(outState)
    }

    fun requestReturnToParentOnFinish() {
        returnToParentOnFinish = true
    }

    /** An explicit user exit; never called for Home, onPause or ordinary onStop. */
    fun requestExit(requestNativeQuit: (() -> Unit)? = null) {
        if (launchRejected || quitRequested || activity.isFinishing) return
        quitRequested = true
        exitGuard.requestExit()
        if (requestNativeQuit == null) {
            activity.finish()
            return
        }

        // Give an engine its native quit event first, without racing Surface destruction.
        // If JNI blocks the UI thread, the independent worker still reclaims the process.
        finishHandler.postDelayed(finishAfterQuit, NATIVE_QUIT_GRACE_PERIOD_MS)
        runCatching(requestNativeQuit).onFailure { error ->
            Timber.tag(logTag).w(error, "Failed to request graphical engine shutdown")
            activity.finish()
        }
    }

    /** Called by the Activity's finish override before super.finish(). */
    fun onFinish() {
        if (launchRejected) return
        finishHandler.removeCallbacks(finishAfterQuit)
        exitGuard.requestExit()
        if (!returnToParentOnFinish || parentNavigationStarted || !activity.isTaskRoot) return
        parentNavigationStarted = true
        runCatching {
            activity.startActivity(
                Intent(activity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            )
        }.onFailure { error ->
            Timber.tag(logTag).w(error, "Failed to navigate back to the editor")
        }
    }

    /** Must precede super.onDestroy(): native_app_glue / engine joins can block forever. */
    fun onBeforeDestroy() {
        finishHandler.removeCallbacks(finishAfterQuit)
        if (!launchRejected && activity.isFinishing && !activity.isChangingConfigurations) exitGuard.requestExit()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback?.let(activity.onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
            backCallback = null
        }
    }

    fun onDestroy() {
        try {
            if (overlayAttached) {
                viewTreeOwner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
                overlayView = null
                overlayAttached = false
            }
            viewTreeOwner.clear()

            if (prepared) {
                NativeStdStreamRedirect.stop()
                environmentOwnerId?.let(NativeLaunchEnvironment::clear)
                environmentOwnerId = null
                prepared = false
            }
        } finally {
            if (!launchRejected && activity.isFinishing && !activity.isChangingConfigurations) exitGuard.onDestroyed()
        }
    }

    private fun dispatchOverlayEvent(event: Lifecycle.Event) {
        if (overlayAttached) viewTreeOwner.handleLifecycleEvent(event)
    }

    private companion object {
        const val NATIVE_QUIT_GRACE_PERIOD_MS = 800L
    }
}

private class GraphicalRuntimeViewTreeOwner :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    init {
        savedStateController.performAttach()
    }

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    fun performRestore(savedInstanceState: Bundle?) {
        savedStateController.performRestore(savedInstanceState)
    }

    fun performSave(outState: Bundle) {
        savedStateController.performSave(outState)
    }

    fun handleLifecycleEvent(event: Lifecycle.Event) {
        lifecycleRegistry.handleLifecycleEvent(event)
    }

    fun clear() {
        store.clear()
    }
}
