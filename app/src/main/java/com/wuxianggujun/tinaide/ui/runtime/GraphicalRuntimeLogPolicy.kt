package com.wuxianggujun.tinaide.ui.runtime

/** Unknown tags belong to the program by default, regardless of its graphics engine. */
internal object GraphicalRuntimeLogPolicy {
    private val windowTags = setOf(
        "ActivityThread", "View", "ViewRootImpl", "ViewRootImplExtImpl", "VRI", "DecorView",
        "SurfaceView", "WindowManager", "WindowManagerGlobal", "WindowOnBackDispatcher",
        "InsetsController", "InsetsSourceConsumer", "InputMethodManager", "InputMethodManagerUtils",
        "InputEventReceiver", "ImeTracker", "ImeFocusController", "Choreographer",
        "GraphicsEnvironment", "CompatibilityChangeReporter", "CompatChangeReporter",
        "OpenGLRenderer", "HWUI", "nativeloader",
    )
    private val hostTags = setOf(
        "TINA_SDL_HOST", "SDLAudio", "SDLControllerManager", "hidapi", "SdlNativeCompat",
        "TinaApplication", "ExternalSdlActivity", "ExternalSdl2Activity", "ExternalNativeActivity",
        "GraphicalRuntimeOverlay", "NativeStdStreamRedirect", "SdlAssetRedirect",
        // Vendor frame-rate diagnostics run inside the app process on every touch.
        "DynamicFramerate",
        // logcat filters match full tags; include known decorations before its initial tail limit.
        "DynamicFramerate [AnimationSpeedAware]", "DynamicFramerate [DynamicFrameRateController]",
    )
    private val programFilters = buildList {
        add("*:V")
        windowTags.forEach { add("$it:S") }
        hostTags.forEach { add("$it:W") }
    }

    fun logcatFilters(showAllProcessLogs: Boolean): List<String> =
        if (showAllProcessLogs) listOf("*:V") else programFilters

    fun accepts(tag: String, level: Char, showAllProcessLogs: Boolean): Boolean {
        if (showAllProcessLogs) return true
        // Android decorates some per-window tags with an Activity name or object identity.
        val windowTag = tag.substringBefore('[').substringBefore('@').trimEnd()
        if (windowTag in windowTags) return false
        return windowTag !in hostTags || level in "WEF"
    }
}
