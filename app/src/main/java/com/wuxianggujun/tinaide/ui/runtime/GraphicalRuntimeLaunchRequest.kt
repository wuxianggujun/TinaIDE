package com.wuxianggujun.tinaide.ui.runtime

import com.wuxianggujun.tinaide.core.compile.SdlOrientation

/** Immutable launch request passed from a completed build to an Android graphical host. */
sealed interface GraphicalRuntimeLaunchRequest {
    val libraryPath: String
    val environment: Map<String, String>
    val enableFloatingLog: Boolean

    data class Sdl(
        override val libraryPath: String,
        override val environment: Map<String, String> = emptyMap(),
        val preferredSdlMajor: Int? = null,
        val orientation: SdlOrientation = SdlOrientation.AUTO,
        override val enableFloatingLog: Boolean = false,
    ) : GraphicalRuntimeLaunchRequest

    data class NativeActivity(
        override val libraryPath: String,
        override val environment: Map<String, String> = emptyMap(),
        override val enableFloatingLog: Boolean = false,
    ) : GraphicalRuntimeLaunchRequest
}
