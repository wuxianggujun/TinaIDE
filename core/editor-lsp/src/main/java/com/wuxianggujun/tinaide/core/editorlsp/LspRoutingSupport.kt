package com.wuxianggujun.tinaide.core.editorlsp

import java.io.File

enum class LspAttachmentRoute {
    NONE,
    CXX,
    PLUGIN,
    BUILTIN_CMAKE,
    BUILTIN_MAKE
}

object LspRoutingSupport {
    fun resolveAttachmentRoute(
        file: File,
        editorLspEnabled: Boolean,
        builtinCmakeLspEnabled: Boolean,
        cxxExtensions: Set<String>,
        hasPluginServer: Boolean,
    ): LspAttachmentRoute {
        if (builtinCmakeLspEnabled && CMakeLanguageSupport.isCMakeFile(file)) {
            return LspAttachmentRoute.BUILTIN_CMAKE
        }
        if (MakeLanguageSupport.isMakefile(file)) {
            return LspAttachmentRoute.BUILTIN_MAKE
        }
        if (!editorLspEnabled) {
            return LspAttachmentRoute.NONE
        }
        if (file.extension.lowercase() in cxxExtensions) {
            return LspAttachmentRoute.CXX
        }
        if (hasPluginServer) {
            return LspAttachmentRoute.PLUGIN
        }
        return LspAttachmentRoute.NONE
    }
}
