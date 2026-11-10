package com.wuxianggujun.tinaide.ui.runtime

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.IntSize
import androidx.core.view.doOnAttach
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
    private var overlayPopup: PopupWindow? = null
    private var overlayContainer: ViewGroup? = null
    private var overlayLayoutListener: View.OnLayoutChangeListener? = null
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

        viewTreeOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        fun bounds(): IntSize = IntSize(
            container.rootView.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels,
            container.rootView.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels,
        )
        val windowBounds = mutableStateOf(bounds())
        var positionX = 16
        var positionY = (windowBounds.value.height * 0.2f).toInt()
        val popup = PopupWindow(activity).apply {
            width = ViewGroup.LayoutParams.WRAP_CONTENT
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            isFocusable = false
            isTouchable = true
            isOutsideTouchable = false
            isClippingEnabled = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        fun updatePosition() {
            positionX = positionX.coerceIn(0, (windowBounds.value.width - (overlayView?.width ?: 0)).coerceAtLeast(0))
            positionY = positionY.coerceIn(0, (windowBounds.value.height - (overlayView?.height ?: 0)).coerceAtLeast(0))
            if (popup.isShowing) popup.update(positionX, positionY, -1, -1)
        }

        val composeView = ComposeView(activity).apply {
            setViewTreeLifecycleOwner(viewTreeOwner)
            setViewTreeViewModelStoreOwner(viewTreeOwner)
            setViewTreeSavedStateRegistryOwner(viewTreeOwner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                TinaIDETheme {
                    GraphicalRuntimeOverlay(
                        enableFloatingLog = enableFloatingLog,
                        onExit = onExit,
                        windowBounds = windowBounds.value,
                        onDragWindow = { delta ->
                            positionX += delta.x.toInt()
                            positionY += delta.y.toInt()
                            updatePosition()
                        },
                    )
                }
            }
        }
        // NativeActivity gives the main window surface to the engine. A child view
        // shares that surface and can be overwritten; an attached popup owns a
        // separate window above it, without SYSTEM_ALERT_WINDOW or full-screen touch capture.
        // PopupWindow inserts its own decor root. Compose resolves the window
        // recomposer from that root, not from the inner ComposeView. Install the
        // owners before ViewGroup dispatches attachment to its Compose child.
        popup.contentView = object : FrameLayout(activity) {
            override fun onAttachedToWindow() {
                rootView.setViewTreeLifecycleOwner(viewTreeOwner)
                rootView.setViewTreeViewModelStoreOwner(viewTreeOwner)
                rootView.setViewTreeSavedStateRegistryOwner(viewTreeOwner)
                super.onAttachedToWindow()
            }
        }.apply { addView(composeView) }
        overlayView = composeView
        overlayPopup = popup
        overlayContainer = container
        overlayAttached = true
        composeView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updatePosition() }
        overlayLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            windowBounds.value = bounds()
            updatePosition()
        }.also(container::addOnLayoutChangeListener)
        container.doOnAttach {
            container.post {
                if (overlayAttached && container.isAttachedToWindow && !activity.isFinishing && !activity.isDestroyed) {
                    popup.showAtLocation(container, Gravity.TOP or Gravity.START, positionX, positionY)
                }
            }
        }
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
                overlayLayoutListener?.let { overlayContainer?.removeOnLayoutChangeListener(it) }
                overlayLayoutListener = null
                overlayContainer = null
                overlayPopup?.dismiss()
                overlayPopup = null
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
