package com.wuxianggujun.tinaide.ui.runtime

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
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
) {
    private val viewTreeOwner = GraphicalRuntimeViewTreeOwner()
    private var environmentOwnerId: String? = null
    private var prepared = false
    private var overlayAttached = false
    private var overlayView: ComposeView? = null

    @Volatile
    private var returnToParentOnFinish = false

    @Volatile
    private var parentNavigationStarted = false

    /** Must run before Activity.super.onCreate while the overlay lifecycle is INITIALIZED. */
    fun onPreCreate(
        savedInstanceState: Bundle?,
        launchIntent: Intent,
    ) {
        check(!prepared) { "Graphical runtime host was prepared more than once" }
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
    }

    /** Attaches the shared floating return control above the runtime's rendering surface. */
    fun attachOverlay(
        container: ViewGroup,
        enableFloatingLog: Boolean,
        onExit: () -> Unit,
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

    /** Called by the Activity's finish override before super.finish(). */
    fun onFinish() {
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

    fun onDestroy() {
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
    }

    private fun dispatchOverlayEvent(event: Lifecycle.Event) {
        if (overlayAttached) viewTreeOwner.handleLifecycleEvent(event)
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
