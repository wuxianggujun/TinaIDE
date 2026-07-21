package com.wuxianggujun.tinaide.core.editorlsp

/**
 * 编辑器语言服务连接状态（UI 状态栏与 LspEditorManager 共用）。
 */
enum class EditorStatus {
    Ready,
    Connecting,
    Busy,
    NoLsp,
    Error,
}
