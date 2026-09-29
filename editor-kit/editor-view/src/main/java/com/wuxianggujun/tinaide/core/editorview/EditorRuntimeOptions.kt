package com.wuxianggujun.tinaide.core.editorview

/** Optional host integrations. The editor itself never reads application preferences. */
class EditorRuntimeOptions(
    val onFontSizeChanged: (Float) -> Unit = {},
    val imeDiagnosticsEnabled: () -> Boolean = { false },
    val layoutDiagnosticsEnabled: () -> Boolean = { false },
    val touchDiagnostics: () -> EditorTouchDiagnosticsFlags = { EditorTouchDiagnosticsFlags() }
)

data class EditorTouchDiagnosticsFlags(
    val enabled: Boolean = false,
    val verbose: Boolean = false,
    val scale: Boolean = false,
    val focus: Boolean = false,
    val scroll: Boolean = false,
    val fling: Boolean = false
)
