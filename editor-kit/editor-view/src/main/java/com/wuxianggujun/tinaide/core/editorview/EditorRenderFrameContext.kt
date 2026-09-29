package com.wuxianggujun.tinaide.core.editorview

/**
 * 渲染一帧时共享的文本读取与扫描依赖。
 *
 * 之前是 `data class` + 每帧新建 + lineTextProvider lambda 捕获 —— 60fps 下每秒 60 个
 * 小对象 + 60 个捕获闭包。现在改为可复用的类，由 EditorRenderer 持有单实例，
 * 每帧只更新 var 字段（`prepare`），render 结束不用 reset（下一帧 prepare 会覆盖）。
 */
internal class EditorRenderFrameContext private constructor(
    private val textRenderer: TextRenderer?,
    private var lineTextProviderOverride: ((Int) -> String)?
) {
    // 生产构造：绑定 TextRenderer，lineText 走内部状态，避免每帧 lambda 捕获。
    internal constructor(textRenderer: TextRenderer) : this(textRenderer, null)

    // 测试构造：直接用显式 lineTextProvider，跳过 TextRenderer 依赖。
    internal constructor(
        state: EditorState,
        textVersion: Long,
        textScanCache: EditorTextScanCache,
        bracketSnapshotCache: EditorBracketSnapshotCache,
        lineTextProvider: (Int) -> String
    ) : this(null, lineTextProvider) {
        prepare(state, textVersion, textScanCache, bracketSnapshotCache)
    }

    private var _state: EditorState? = null
    val state: EditorState get() = _state!!
    var textVersion: Long = 0L
        private set
    private var _textScanCache: EditorTextScanCache? = null
    val textScanCache: EditorTextScanCache get() = _textScanCache!!
    private var _bracketSnapshotCache: EditorBracketSnapshotCache? = null
    val bracketSnapshotCache: EditorBracketSnapshotCache get() = _bracketSnapshotCache!!

    fun prepare(
        state: EditorState,
        textVersion: Long,
        textScanCache: EditorTextScanCache,
        bracketSnapshotCache: EditorBracketSnapshotCache
    ) {
        this._state = state
        this.textVersion = textVersion
        this._textScanCache = textScanCache
        this._bracketSnapshotCache = bracketSnapshotCache
    }

    fun lineText(line: Int): String {
        val override = lineTextProviderOverride
        if (override != null) return override(line)
        return textRenderer!!.lineText(state, line)
    }
}
