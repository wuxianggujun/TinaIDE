package com.wuxianggujun.tinaide.core.editorview

enum class WhitespaceRenderMode {
    NONE,
    BOUNDARY,
    ALL
}

data class EditorConfig(
    val showLineNumbers: Boolean = true,
    /**
     * 是否“固定行号栏/侧边栏”，使其不跟随横向滚动。
     *
     * - `true`：行号栏固定在左侧，只滚动文本区（更接近 VS Code 等）
     * - `false`：行号栏与文本一起横向滚动（更接近 Sora 默认行为）
     */
    val pinLineNumber: Boolean = false,
    val useRelativeLineNumbers: Boolean = false,
    val fontSizeSp: Float = 14f,
    val imeWindowChars: Int = 512,
    val imeWindowMarginChars: Int = 128,
    val lineRenderCacheSize: Int = 512,
    val positionCacheSize: Int = 64,
    val gestureSuppressionMs: Long = 150L,
    val scrollFlingEnabled: Boolean = true,
    val singleDirectionDragging: Boolean = true,
    val singleDirectionFling: Boolean = true,
    val scrollbarFadeDelayMs: Long = 900L,
    val scrollbarFadeDurationMs: Long = 260L,
    val autoIndent: Boolean = true,
    val tabSize: Int = 4,
    val insertSpacesForTabs: Boolean = true,
    val completionCaseSensitive: Boolean = false,
    val wordWrap: Boolean = true,
    val codeFolding: Boolean = true,
    val rainbowBrackets: Boolean = true,
    val rainbowBracketsMaxLines: Int = 5000,
    val bracketPairGuides: Boolean = true,
    val renderWhitespace: WhitespaceRenderMode = WhitespaceRenderMode.NONE,
    val selectionHandleRadiusRatio: Float = 0.56f,
    val selectionHandleMinRadiusPx: Float = 20f,
    val selectionHandleMaxRadiusPx: Float = 38f,
    val selectionHandleHitSlopRatio: Float = 1.0f,
    val selectionHandleHitMinExtraPx: Float = 26f,
    val selectionMagnifierEnabled: Boolean = true
)
