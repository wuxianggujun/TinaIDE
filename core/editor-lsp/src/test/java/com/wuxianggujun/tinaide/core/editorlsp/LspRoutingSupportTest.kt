package com.wuxianggujun.tinaide.core.editorlsp

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class LspRoutingSupportTest {

    private val cxxExtensions = setOf("c", "cc", "cpp")

    @Test
    fun resolveAttachmentRoute_shouldKeepMakeOnBuiltinRouteWhenEditorLspDisabled() {
        val route = LspRoutingSupport.resolveAttachmentRoute(
            file = File("workspace/Makefile"),
            editorLspEnabled = false,
            builtinCmakeLspEnabled = false,
            cxxExtensions = cxxExtensions,
            hasPluginServer = true
        )

        assertThat(route).isEqualTo(LspAttachmentRoute.BUILTIN_MAKE)
    }

    @Test
    fun resolveAttachmentRoute_shouldKeepBuiltinCmakeRouteWhenEditorLspDisabled() {
        val route = LspRoutingSupport.resolveAttachmentRoute(
            file = File("workspace/CMakeLists.txt"),
            editorLspEnabled = false,
            builtinCmakeLspEnabled = true,
            cxxExtensions = cxxExtensions,
            hasPluginServer = true
        )

        assertThat(route).isEqualTo(LspAttachmentRoute.BUILTIN_CMAKE)
    }

    @Test
    fun resolveAttachmentRoute_shouldDisableExternalRoutesWhenEditorLspDisabled() {
        val route = LspRoutingSupport.resolveAttachmentRoute(
            file = File("workspace/main.cpp"),
            editorLspEnabled = false,
            builtinCmakeLspEnabled = false,
            cxxExtensions = cxxExtensions,
            hasPluginServer = true
        )

        assertThat(route).isEqualTo(LspAttachmentRoute.NONE)
    }

    @Test
    fun resolveAttachmentRoute_shouldRouteCxxFilesToCxxWhenEditorLspEnabled() {
        val route = LspRoutingSupport.resolveAttachmentRoute(
            file = File("workspace/main.cpp"),
            editorLspEnabled = true,
            builtinCmakeLspEnabled = false,
            cxxExtensions = cxxExtensions,
            hasPluginServer = false
        )

        assertThat(route).isEqualTo(LspAttachmentRoute.CXX)
    }

    @Test
    fun resolveAttachmentRoute_shouldFallbackToPluginRouteWhenAvailable() {
        val route = LspRoutingSupport.resolveAttachmentRoute(
            file = File("workspace/build.gradle.kts"),
            editorLspEnabled = true,
            builtinCmakeLspEnabled = false,
            cxxExtensions = cxxExtensions,
            hasPluginServer = true
        )

        assertThat(route).isEqualTo(LspAttachmentRoute.PLUGIN)
    }
}
