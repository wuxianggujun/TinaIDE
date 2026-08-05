package com.wuxianggujun.tinaide.ui.nativeactivity

import androidx.annotation.Keep

@Keep
internal object NativeActivityHostBridge {
    const val ERROR_NOT_CONFIGURED = 1
    const val ERROR_DEPENDENCY_LOAD = 2
    const val ERROR_MAIN_LOAD = 3
    const val ERROR_ENTRY_MISSING = 4
    const val ERROR_RAYLIB_MAIN_MISSING = 5
    const val ERROR_ENTRY_RECURSION = 6
    const val ERROR_MULTIPLE_ENTRIES = 7

    init {
        System.loadLibrary("tina_native_activity_host")
    }

    fun configure(mainLibraryPath: String, dependencyLibraryPaths: List<String>): Boolean =
        nativeConfigure(mainLibraryPath, dependencyLibraryPaths.toTypedArray())

    private external fun nativeConfigure(
        mainLibraryPath: String,
        dependencyLibraryPaths: Array<String>,
    ): Boolean
}
