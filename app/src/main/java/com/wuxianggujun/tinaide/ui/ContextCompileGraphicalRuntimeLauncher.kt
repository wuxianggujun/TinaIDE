package com.wuxianggujun.tinaide.ui

import com.wuxianggujun.tinaide.ui.runtime.GraphicalRuntimeLaunchRequest

internal class ContextCompileGraphicalRuntimeLauncher(
    private val sdlLauncher: ContextCompileSdlLauncher,
    private val nativeActivityLauncher: ContextCompileNativeActivityLauncher,
) : CompileUiEventObserver.GraphicalRuntimeLauncher {
    override suspend fun open(request: GraphicalRuntimeLaunchRequest) {
        when (request) {
            is GraphicalRuntimeLaunchRequest.Sdl -> sdlLauncher.open(request)
            is GraphicalRuntimeLaunchRequest.NativeActivity -> nativeActivityLauncher.open(request)
        }
    }
}
