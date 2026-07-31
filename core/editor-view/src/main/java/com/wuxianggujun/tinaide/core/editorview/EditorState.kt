package com.wuxianggujun.tinaide.core.editorview

import android.graphics.Typeface
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wuxianggujun.tinaide.core.editorlsp.SignatureHelpResult
import com.wuxianggujun.tinaide.core.textengine.Position
import com.wuxianggujun.tinaide.core.textengine.TextBuffer
import com.wuxianggujun.tinaide.core.textengine.TextChange
import com.wuxianggujun.tinaide.core.textengine.TextScanKernel
import com.wuxianggujun.tinaide.core.textengine.WordBounds
import com.wuxianggujun.tinaide.core.treesitter.SyntaxHighlighter
import com.wuxianggujun.tinaide.core.treesitter.TreeSitterFoldingProvider.FoldRegion
import java.io.File
import java.util.LinkedHashMap
import kotlin.math.floor
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import timber.log.Timber

@Stable
class EditorState(
    override val textBuffer: TextBuffer,
    file: File? = null,
    val projectRootPath: String? = null,
    config: EditorConfig = EditorConfig()
) : EditorStateSnapshot,
    EditorEditOperations {
    private companion object {
        private const val SLOW_OPERATION_THRESHOLD_MS = 16L
        private const val SLOW_OPERATION_LOG_INTERVAL_MS = 800L
    }

    private var lastSlowOperationLogAtMs: Long = 0L

    var file by mutableStateOf(file)
        private set

    private var _config by mutableStateOf(config)
    var config: EditorConfig
        get() = _config
        set(value) {
            val old = _config
            if (old == value) return
            _config = value
            onConfigChanged(old = old, new = value)
        }
    var typeface by mutableStateOf<Typeface>(Typeface.MONOSPACE)
    var colorScheme by mutableStateOf(EditorColorScheme.builtinGray())

    fun retargetFile(newFile: File?) {
        file = newFile
    }

    override var cursorOffset by mutableStateOf(0)
    override var selectionRange by mutableStateOf<OffsetRange?>(null)

    private var cachedCursorOffset = -1
    private var cachedCursorVersion = -1L
    private var cachedCursorPosition = Position(0, 0)

    val cursorPosition: Position
        get() {
            val offset = cursorOffset.coerceIn(0, textBuffer.length)
            val version = textBuffer.version
            if (offset == cachedCursorOffset && version == cachedCursorVersion) {
                return cachedCursorPosition
            }
            val pos = textBuffer.offsetToPosition(offset)
            cachedCursorOffset = offset
            cachedCursorVersion = version
            cachedCursorPosition = pos
            return pos
        }

    val cursorLine: Int get() = cursorPosition.line
    val cursorColumn: Int get() = cursorPosition.column

    private fun cursorLineColumn(): Pair<Int, Int> {
        val pos = cursorPosition
        return pos.line to pos.column
    }
    var fontSizeSp by mutableStateOf(config.fontSizeSp)
    var pinLineNumber by mutableStateOf(config.pinLineNumber)

    var scrollOffsetPx by mutableStateOf(0f)
    var scrollOffsetXPx by mutableStateOf(0f)
    var viewportHeightPx by mutableStateOf(1f)
    var viewportWidthPx by mutableStateOf(1f)

    // 文本区起始位置（canvas 坐标）。用于处理“行号栏是否跟随横向滚动”的坐标收敛。
    var contentStartXPx by mutableStateOf(0f)
    var lineHeightPx by mutableStateOf(1f)
    var charWidthPx by mutableStateOf(1f)
    var isFocused by mutableStateOf(false)
    var cursorBlinkVisible by mutableStateOf(true)

    /**
     * 双指缩放锚点：用于在字体缩放导致 wordWrap 重排时，保持“手指附近的文本”尽可能稳定。
     *
     * 为什么需要它：
     * - 仅按像素比例缩放 scrollOffset（(old+focus)*scale-focus）在 wordWrap 场景下会失效：
     *   wrapColumns 会随着字宽变化而变化，视觉行会重排，导致缩放过程中内容“漂移”。
     * - 我们改为记录缩放发生时焦点附近的 charOffset，在下一帧 metrics 更新后按新布局回推 scrollOffset。
     *
     * 生命周期：
     * - 手势侧在修改 fontSize 前写入
     * - 下一帧 [updateMetrics] 应用并清空
     */
    internal data class PendingScaleAnchor(
        val charOffset: Int,
        val focusX: Float,
        val focusY: Float,
        val focusYInVisualLineRatio: Float
    )

    internal var pendingScaleAnchor: PendingScaleAnchor? = null

    /**
     * wordWrap 缩放策略（对齐 Sora）：
     * - 双指缩放过程中，如果每次 fontSize 改变都立刻用新的 charWidth 重新计算 wrapColumns，
     *   会导致视觉行重排，从而出现“手指附近文本漂移/回弹”的观感。
     * - Sora 的做法是：缩放过程中冻结 wordwrap 布局，结束后再重建并做一次锚点回推。
     *
     * 这里用 frozenWordWrapColumns 实现同样的冻结效果：
     * - frozen != null 时，visualLineMap() 使用 frozen 的 wrapColumns（保持视觉行分段不变）
     * - 缩放结束后再清空 frozen，让布局按新字体度量重新分段，并结合 [PendingScaleAnchor] 做最终对齐
     */
    internal var frozenWordWrapColumns by mutableStateOf<Int?>(null)
        private set

    internal fun isWordWrapLayoutFrozen(): Boolean = frozenWordWrapColumns != null

    internal fun freezeWordWrapLayoutIfNeeded() {
        if (!config.wordWrap) return
        if (frozenWordWrapColumns != null) return
        val safeCharWidth = charWidthPx.coerceAtLeast(1f)
        val safeViewportWidth = viewportWidthPx.coerceAtLeast(1f)
        frozenWordWrapColumns = (safeViewportWidth / safeCharWidth).toInt().coerceAtLeast(1)
    }

    internal fun unfreezeWordWrapLayout() {
        if (frozenWordWrapColumns == null) return
        frozenWordWrapColumns = null
    }

    /**
     * 可选的“列号 -> X 像素”解析器（X 为文本行内坐标：从行首开始累加的宽度）。
     *
     * 为什么需要它：
     * - 编辑器渲染与命中测试已经统一到 [EditorLineLayoutCache]（prefix advances）。
     * - 但滚动对齐（ensureCursorVisible / 自动滚动）如果仍用 `column * charWidthPx`，
     *   在包含 CJK/组合字符/不同 glyph advance 的情况下会出现“内容突然左右偏移”的观感差异。
     *
     * 由 UI 层（Session）注入，确保使用与渲染一致的 Paint/字体度量。
     */
    internal var columnXInTextPxResolver: ((line: Int, column: Int) -> Float)? = null

    // 用于触发 Compose 重组的状态，每次文本变化时更新
    internal var textVersion by mutableStateOf(textBuffer.version)
        private set

    internal var highlightVersion by mutableStateOf(0L)
        private set

    fun notifyHighlightChanged() {
        highlightVersion++
        bumpStylingVersion()
    }

    internal var semanticTokensVersion by mutableStateOf(0L)
        private set

    /**
     * 统一的 styling 版本号：每次 syntax 或 semantic 更新都推进，最晚到达者负责递增。
     *
     * 作用：让渲染 / cache / LaunchedEffect 可以通过单一 key 感知 styling 变化，
     * 消除「新语法 + 旧语义」这种短暂错位帧（因为两类更新都同步推进同一计数器，
     * 下游 key 变化只需要比较一个值）。
     */
    internal var effectiveStylingVersion by mutableStateOf(0L)
        private set

    private fun bumpStylingVersion() {
        effectiveStylingVersion++
    }

    private val maxLineWidthTracker = EditorMaxLineWidthTracker(
        object : EditorMaxLineWidthTracker.Host {
            override val textBuffer: TextBuffer get() = this@EditorState.textBuffer
            override val charWidthPx: Float get() = this@EditorState.charWidthPx
            override val viewportWidthPx: Float get() = this@EditorState.viewportWidthPx
            override val cursorLine: Int get() = this@EditorState.cursorLine
            override val visibleLines: IntRange get() = this@EditorState.visibleLines
            override val wordWrapEnabled: Boolean get() = this@EditorState.config.wordWrap
            override val isWordWrapLayoutFrozen: Boolean get() = this@EditorState.isWordWrapLayoutFrozen()

            override fun lineVisualColumns(lineText: String): Int = lineVisualColumnsForWidth(lineText)

            override fun currentVisualLineMap(): EditorVisualLineMapper.VisualLineMap = visualLineMap()

            override fun resolveVisibleIndexForVisualLine(
                map: EditorVisualLineMapper.VisualLineMap,
                visualLine: Int
            ): Int = this@EditorState.resolveVisibleIndexForVisualLine(map, visualLine)
        }
    )

    // 视觉行映射（folding + wordWrap）委托给 [EditorVisualLineMapper]：该类维护视觉行映射缓存与
    // 按文档行的 wrap segmentCount 缓存，行为与原内联实现严格一致（见该类文档说明的两处刻意保留现状）。
    // 折叠层产出的 lineMap() 经 Host 注入，转接到 [EditorFoldingManager]。
    private val visualLineMapper = EditorVisualLineMapper(
        object : EditorVisualLineMapper.Host {
            override val textBuffer: TextBuffer get() = this@EditorState.textBuffer
            override val charWidthPx: Float get() = this@EditorState.charWidthPx
            override val viewportWidthPx: Float get() = this@EditorState.viewportWidthPx
            override val wordWrapEnabled: Boolean get() = this@EditorState.config.wordWrap
            override val tabSize: Int get() = this@EditorState.config.tabSize
            override val codeFoldingEnabled: Boolean get() = this@EditorState.config.codeFolding
            override val frozenWordWrapColumns: Int? get() = this@EditorState.frozenWordWrapColumns
            override val foldRegionsDocumentVersion: Long get() = foldingManager.foldRegionsDocumentVersion
            override val foldDataVersion: Int get() = foldingManager.foldDataVersion
            override fun lineMap(): EditorFoldingManager.LineMap = foldingManager.lineMap()
        }
    )

    // 代码折叠（按行隐藏 + 折叠映射 LineMap）委托给 [EditorFoldingManager]：该类维护折叠区间数据、
    // 折叠映射缓存与全部折叠查询/切换 API，行为与原内联实现严格一致。foldDataVersion 等仍由
    // mutableStateOf 承载，Compose 跨对象读取仍能触发重组（见该类文档说明）。EditorState 保留瘦委托，
    // 使模块内 16+ 文件经 state.x() 的调用点零改动。
    private val foldingManager = EditorFoldingManager(
        object : EditorFoldingManager.Host {
            override val textBuffer: TextBuffer get() = this@EditorState.textBuffer
            override val codeFoldingEnabled: Boolean get() = this@EditorState.config.codeFolding
            override val tabSize: Int get() = this@EditorState.config.tabSize
            override val gutterDecorations get() = this@EditorState.gutterDecorations
            override val diagnosticLinesSorted: IntArray get() = this@EditorState.diagnosticLinesSorted
            override val cursorOffset: Int get() = this@EditorState.cursorOffset

            override fun moveCursorTo(offset: Int) = this@EditorState.moveCursorTo(offset)
            override fun clampScroll() = this@EditorState.clampScroll()
        }
    )

    // Hover / SignatureHelp 状态机委托给 [EditorHoverSignatureController]：两块彼此独立、只共享
    // cursorPosition，与补全/snippet 无耦合。UiState 仍由 mutableStateOf 承载，Compose 跨对象读取
    // 仍能触发重组（见该类文档说明）。EditorState 保留瘦委托与 get-only 镜像属性，使模块内 9+ 文件
    // 经 state.x 的读取与 4 个测试 fixture 调用点零改动。
    private val hoverSignatureController = EditorHoverSignatureController(
        object : EditorHoverSignatureController.Host {
            override val cursorPosition: Position get() = this@EditorState.cursorPosition
            override val onRequestHover: (suspend (Position) -> String?)? get() = this@EditorState.onRequestHover
            override val onRequestSignatureHelp: (suspend (Position) -> SignatureHelpResult?)?
                get() = this@EditorState.onRequestSignatureHelp
        }
    )

    // 补全 + snippet 状态机委托给 [EditorCompletionController]：两块双向耦合（snippet 占位符聚焦弹出
    // choice 补全、取消 snippet 关闭补全、请求补全清除 snippet choice 标记），整体抽出最干净，互调在
    // 该类内变成普通方法调用。补全状态字段仍由 mutableStateOf 承载，Compose 跨对象读取仍触发重组。
    // EditorState 保留瘦委托与 get-only 镜像属性，使模块内多个 Compose 文件与测试调用点零改动。
    // 注意：applyCompletion 仍保留在本类（editorApplyCompletion 接收整个 EditorState，避免透传），
    // completionQueryFromCursor 也保留在本类（有独立测试且被交互控制器复用），二者经 Host 回调暴露。
    private val completionController = EditorCompletionController(
        object : EditorCompletionController.Host {
            override val textBuffer: TextBuffer get() = this@EditorState.textBuffer
            override val cursorPosition: Position get() = this@EditorState.cursorPosition
            override val completionCaseSensitive: Boolean get() = this@EditorState.config.completionCaseSensitive
            override val onRequestCompletion: (suspend (Position, Char?) -> EditorCompletionFetchResult)?
                get() = this@EditorState.onRequestCompletion

            override fun completionQueryFromCursor(): String = this@EditorState.completionQueryFromCursor()

            override fun moveCursorTo(offset: Int, clearSelection: Boolean) =
                this@EditorState.moveCursorTo(offset, clearSelection)

            override fun setSelectionRange(range: OffsetRange?) {
                this@EditorState.selectionRange = range
            }

            override fun applyCompletion(item: EditorCompletionItem) = this@EditorState.applyCompletion(item)
        }
    )

    // 补全 + snippet 状态委托给 [completionController]（见上方构造）。以下为 get-only 委托，使模块内
    // 多个 Compose 文件与测试经 state.x 的直接读取零改动。Compose 跨对象读取这些 mutableState 仍触发重组。
    val completionItems: List<EditorCompletionItem> get() = completionController.completionItems
    val completionSelectedIndex: Int get() = completionController.completionSelectedIndex
    val completionQuery: String get() = completionController.completionQuery
    val completionUiState: CompletionUiState get() = completionController.completionUiState
    val showCompletion: Boolean get() = completionController.showCompletion
    internal val cachedCompletionResults: List<EditorCompletionItem>
        get() = completionController.cachedCompletionResults
    internal val cachedCompletionPrefix: String get() = completionController.cachedCompletionPrefix
    internal val snippetChoiceCompletionActive: Boolean get() = completionController.snippetChoiceCompletionActive
    internal val activeSnippetSession: SnippetSession? get() = completionController.activeSnippetSession
    // Hover / SignatureHelp 状态机委托给 [hoverSignatureController]（见上方构造）。以下为 get-only 委托，
    // 使模块内 9+ 文件经 state.x 的直接读取与 4 个 hover/signature 测试调用点零改动。Compose 跨对象读取
    // 这些 mutableState 仍触发重组（见该类文档说明）。
    val hoverUiState: HoverUiState get() = hoverSignatureController.hoverUiState
    val signatureHelpUiState: SignatureHelpUiState get() = hoverSignatureController.signatureHelpUiState
    val signatureHelpSelectedSignatureIndex: Int? get() = hoverSignatureController.signatureHelpSelectedSignatureIndex

    // Hover / SignatureHelp 的 publish* 入口委托给 [hoverSignatureController]：保留以下 internal 瘦委托，
    // 使模块内测试 fixture（seedVisibleHover / seedVisibleSignatureHelp / seedLoadingSignatureHelp）零改动。
    internal fun publishHoverVisible(markdown: String) =
        hoverSignatureController.publishHoverVisible(markdown)

    internal fun publishSignatureHelpLoading(
        previousResult: SignatureHelpResult?,
        requestId: Long,
        selectedIndex: Int? = signatureHelpSelectedSignatureIndex
    ) = hoverSignatureController.publishSignatureHelpLoading(previousResult, requestId, selectedIndex)

    internal fun publishSignatureHelpVisible(
        result: SignatureHelpResult,
        requestId: Long,
        selectedIndex: Int? = signatureHelpSelectedSignatureIndex
    ) = hoverSignatureController.publishSignatureHelpVisible(result, requestId, selectedIndex)

    var highlighter by mutableStateOf<SyntaxHighlighter?>(null)
    var semanticTokens by mutableStateOf<List<SemanticToken>>(emptyList())
    var semanticTokensByLine by mutableStateOf<Map<Int, List<SemanticToken>>>(emptyMap())
    var diagnostics by mutableStateOf<List<EditorDiagnostic>>(emptyList())
    var diagnosticsByLine by mutableStateOf<Map<Int, List<EditorDiagnostic>>>(emptyMap())
    private var diagnosticLinesSortedRef: Map<Int, List<EditorDiagnostic>>? = null
    private var diagnosticLinesSorted: IntArray = IntArray(0)
        get() {
            val current = diagnosticsByLine
            if (current !== diagnosticLinesSortedRef) {
                diagnosticLinesSortedRef = current
                field = current.keys.toIntArray().also { it.sort() }
            }
            return field
        }
    val gutterDecorations = mutableStateMapOf<Int, GutterDecoration>()

    private val wordWrapLayoutCache = EditorWordWrapLayoutCache()

    fun clearSemanticTokens() {
        if (semanticTokens.isEmpty() && semanticTokensByLine.isEmpty()) return
        semanticTokens = emptyList()
        semanticTokensByLine = emptyMap()
        semanticTokensVersion++
        bumpStylingVersion()
    }

    fun replaceSemanticTokens(tokens: List<SemanticToken>) {
        val groupedByLine = tokens.groupBy { it.line }
        if (semanticTokens == tokens && semanticTokensByLine == groupedByLine) return
        semanticTokens = tokens
        semanticTokensByLine = groupedByLine
        semanticTokensVersion++
        bumpStylingVersion()
    }

    fun mergeSemanticTokens(tokens: List<SemanticToken>) {
        if (tokens.isEmpty()) return

        val groupedByLine = tokens.groupBy { it.line }
        val mergedByLine = semanticTokensByLine.toMutableMap()
        var changed = false

        groupedByLine.forEach { (line, lineTokens) ->
            if (mergedByLine[line] != lineTokens) {
                mergedByLine[line] = lineTokens
                changed = true
            }
        }

        if (!changed) return

        semanticTokensByLine = mergedByLine
        semanticTokens = mergedByLine.values.flatten()
        semanticTokensVersion++
        bumpStylingVersion()
    }

    fun replaceSemanticTokensInLines(lines: IntRange, tokens: List<SemanticToken>) {
        if (lines.isEmpty()) return

        val updatedByLine = semanticTokensByLine.toMutableMap()
        updatedByLine.keys
            .filter { line -> line in lines }
            .forEach(updatedByLine::remove)
        tokens
            .filter { token -> token.line in lines }
            .groupBy { token -> token.line }
            .forEach { (line, lineTokens) -> updatedByLine[line] = lineTokens }

        val normalizedByLine = updatedByLine.toSortedMap()
        if (semanticTokensByLine == normalizedByLine) return

        semanticTokensByLine = normalizedByLine
        semanticTokens = normalizedByLine.values.flatten()
        semanticTokensVersion++
        bumpStylingVersion()
    }

    internal fun applyTextChangeToSemanticTokens(change: TextChange) {
        val currentByLine = semanticTokensByLine
        if (currentByLine.isEmpty()) return

        val startLine = change.startLine.coerceAtLeast(0)
        if (
            change.lineDelta == 0 &&
            change.endLine == change.startLine &&
            change.oldLineBreakCount == 0 &&
            !change.newText.contains('\n')
        ) {
            applySingleLineTextChangeToSemanticTokens(
                currentByLine = currentByLine,
                line = startLine,
                editStartColumn = change.startColumn.coerceAtLeast(0),
                editEndColumn = change.endColumn.coerceAtLeast(change.startColumn),
                columnDelta = change.newText.length - change.oldTextLength
            )
            return
        }

        val oldChangedEndLine = when {
            change.startColumn == 0 &&
                change.endColumn == 0 &&
                change.oldTextEndsWithLineBreak ->
                (change.endLine - 1).coerceAtLeast(startLine)

            else -> change.endLine.coerceAtLeast(startLine)
        }
        val lineDelta = change.lineDelta
        val shiftFromLine = (oldChangedEndLine + 1).coerceAtLeast(0)

        val updatedByLine = LinkedHashMap<Int, List<SemanticToken>>(currentByLine.size)
        currentByLine.entries
            .sortedBy { it.key }
            .forEach { (line, tokens) ->
                if (line in startLine..oldChangedEndLine) {
                    return@forEach
                }

                val targetLine = if (line >= shiftFromLine) {
                    line + lineDelta
                } else {
                    line
                }
                if (targetLine < 0) return@forEach

                updatedByLine[targetLine] = if (targetLine == line) {
                    tokens
                } else {
                    tokens.map { token -> token.copy(line = targetLine) }
                }
            }

        if (updatedByLine == currentByLine) return

        semanticTokensByLine = updatedByLine
        semanticTokens = updatedByLine.values.flatten()
        semanticTokensVersion++
        bumpStylingVersion()
    }

    private fun applySingleLineTextChangeToSemanticTokens(
        currentByLine: Map<Int, List<SemanticToken>>,
        line: Int,
        editStartColumn: Int,
        editEndColumn: Int,
        columnDelta: Int
    ) {
        val currentLineTokens = currentByLine[line] ?: return
        val isInsertion = editStartColumn == editEndColumn
        val adjustedLineTokens = currentLineTokens.mapNotNull { token ->
            val tokenEndColumn = token.startColumn + token.length
            when {
                tokenEndColumn <= editStartColumn -> token
                token.startColumn >= editEndColumn ->
                    token.copy(startColumn = (token.startColumn + columnDelta).coerceAtLeast(0))
                isInsertion && token.startColumn < editStartColumn && tokenEndColumn > editStartColumn -> null
                else -> null
            }
        }
        if (adjustedLineTokens == currentLineTokens) return

        val updatedByLine = LinkedHashMap(currentByLine)
        if (adjustedLineTokens.isEmpty()) {
            updatedByLine.remove(line)
        } else {
            updatedByLine[line] = adjustedLineTokens
        }
        semanticTokensByLine = updatedByLine
        semanticTokens = updatedByLine.values.flatten()
        semanticTokensVersion++
        bumpStylingVersion()
    }

    // 代码折叠相关数据/映射/查询均已迁入 [foldingManager]（见上方构造），EditorState 仅保留瘦委托。

    var useRelativeLineNumbers by mutableStateOf(config.useRelativeLineNumbers)

    var onLineNumberTap: ((line: Int) -> Unit)? = null
    var onLineNumberLongPress: ((line: Int) -> Unit)? = null
    var onRequestCompletion: (suspend (Position, Char?) -> EditorCompletionFetchResult)? = null
    var onRequestHover: (suspend (Position) -> String?)? = null
    var onRequestSignatureHelp: (suspend (Position) -> SignatureHelpResult?)? = null
    var onRequestPeekDefinition: (() -> Unit)? = null
    var onRequestGotoDefinition: (() -> Unit)? = null
    var onRequestFindReferences: (() -> Unit)? = null
    var onRequestGotoTypeDefinition: (() -> Unit)? = null
    var onRequestGotoImplementation: (() -> Unit)? = null
    var onRequestCodeActions: (() -> Unit)? = null
    var onRequestRenameSymbol: (() -> Unit)? = null
    var onRequestSwitchHeaderSource: (() -> Unit)? = null
    var onGutterTap: ((line: Int) -> Unit)? = null
    var onGutterFoldToggle: ((line: Int) -> Unit)? = null
    var onGutterLongPress: ((line: Int) -> Unit)? = null

    private val _events = MutableSharedFlow<EditorEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<EditorEvent> = _events

    /**
     * 当前视口顶部对应的“视觉行”索引（折叠后：视觉行 != 文档行）。
     */
    val firstVisibleLine: Int
        get() = floor(scrollOffsetPx / lineHeightPx).toInt().coerceAtLeast(0)

    val visibleLines by derivedStateOf {
        // 依赖 textVersion 以确保文本变化时重新计算
        @Suppress("UNUSED_EXPRESSION")
        textVersion
        // 依赖 foldDataVersion 以确保折叠状态变化时重新计算
        // （读 foldingManager.foldDataVersion：Compose 快照按 StateObject 身份记录读取，跨对象仍触发重组）
        @Suppress("UNUSED_EXPRESSION")
        foldingManager.foldDataVersion
        // 依赖 config.codeFolding，避免开关变化后 visibleLines 不刷新
        @Suppress("UNUSED_EXPRESSION")
        config.codeFolding
        // 依赖 wordWrap/tabSize 等，避免“设置变化但可见窗口不刷新”
        @Suppress("UNUSED_EXPRESSION")
        config.wordWrap
        @Suppress("UNUSED_EXPRESSION")
        config.tabSize
        // 依赖 wordWrap 冻结状态，保证缩放结束后解除冻结能触发可视窗口重新计算。
        @Suppress("UNUSED_EXPRESSION")
        frozenWordWrapColumns
        val first = firstVisibleLine
        val visibleCount = (viewportHeightPx / lineHeightPx).toInt() + 2
        val maxVisualLine = (visualLineCount() - 1).coerceAtLeast(0)
        val last = (first + visibleCount).coerceAtMost(maxVisualLine)
        first..last
    }

    /**
     * 供 LSP / 语义 Token / 语法高亮等“按文档行”请求使用的可见范围。
     *
     * 注意：折叠后可见的文档行并非连续，但多数外部接口仅接受 IntRange，
     * 这里返回“可见视觉窗口对应的首/末文档行”形成的范围（可能包含被折叠隐藏的行）。
     */
    val visibleDocumentLines: IntRange
        get() {
            val visualRange = visibleLines
            if (visualRange.isEmpty()) return 0..-1
            val totalVisualLines = visualLineCount()
            if (totalVisualLines <= 0) return 0..-1
            val firstVisual = visualRange.first.coerceIn(0, totalVisualLines - 1)
            val lastVisual = visualRange.last.coerceIn(firstVisual, totalVisualLines - 1)
            val firstDoc = docLineForVisualLine(firstVisual)
            val lastDoc = docLineForVisualLine(lastVisual)
            return firstDoc..lastDoc
        }

    internal fun docLineForVisualLine(visualLine: Int): Int {
        val map = visualLineMap()
        if (map.visualLineCount <= 0) return 0
        val safeVisual = visualLine.coerceIn(0, map.visualLineCount - 1)
        val visibleIndex = resolveVisibleIndexForVisualLine(map, safeVisual)
        return map.visibleDocLines.getOrElse(visibleIndex) { 0 }
    }

    internal fun visualLineForDocLine(docLine: Int): Int {
        val map = visualLineMap()
        if (map.visibleDocLineCount <= 0) return 0
        val visibleIndex = resolveVisibleIndexForDocLine(docLine)
        if (visibleIndex < 0) return 0
        return map.firstVisualLineByVisibleIndex.getOrElse(visibleIndex) { 0 }
    }

    internal fun visualLineForPosition(line: Int, column: Int): Int {
        val map = visualLineMap()
        if (map.visibleDocLineCount <= 0 || map.visualLineCount <= 0) return 0
        val visibleIndex = resolveVisibleIndexForDocLine(line)
        if (visibleIndex < 0) return 0
        val firstVisual = map.firstVisualLineByVisibleIndex.getOrElse(visibleIndex) { 0 }
        if (!map.wordWrapEnabled) {
            return firstVisual.coerceIn(0, map.visualLineCount - 1)
        }

        val docLine = map.visibleDocLines.getOrElse(visibleIndex) { 0 }
        val lineText = textBuffer.getLine(docLine)
        val safeColumn = column.coerceIn(0, lineText.length)
        val layout = wordWrapLayoutCache.getWrapLayout(
            line = docLine,
            lineText = lineText,
            textVersion = textBuffer.version,
            wrapColumns = map.wrapColumns,
            tabSize = config.tabSize
        )
        val segmentIndex = layout.segmentIndexForColumn(safeColumn)
        return (firstVisual + segmentIndex).coerceIn(0, map.visualLineCount - 1)
    }

    internal fun visualLineStartColumn(visualLine: Int): Int {
        val map = visualLineMap()
        if (!map.wordWrapEnabled || map.visualLineCount <= 0) return 0
        val safeVisual = visualLine.coerceIn(0, map.visualLineCount - 1)
        val visibleIndex = resolveVisibleIndexForVisualLine(map, safeVisual)
        val docLine = map.visibleDocLines.getOrElse(visibleIndex) { 0 }
        val lineText = textBuffer.getLine(docLine)
        val firstVisual = map.firstVisualLineByVisibleIndex.getOrElse(visibleIndex) { 0 }
        val segmentIndex = (safeVisual - firstVisual).coerceAtLeast(0)
        val layout = wordWrapLayoutCache.getWrapLayout(
            line = docLine,
            lineText = lineText,
            textVersion = textBuffer.version,
            wrapColumns = map.wrapColumns,
            tabSize = config.tabSize
        )
        return layout.startColumnForSegment(segmentIndex).coerceIn(0, lineText.length)
    }

    internal fun visualLineEndColumn(visualLine: Int): Int {
        val map = visualLineMap()
        if (!map.wordWrapEnabled || map.visualLineCount <= 0) {
            val docLine = docLineForVisualLine(visualLine)
            return textBuffer.getLine(docLine).length
        }
        val safeVisual = visualLine.coerceIn(0, map.visualLineCount - 1)
        val visibleIndex = resolveVisibleIndexForVisualLine(map, safeVisual)
        val docLine = map.visibleDocLines.getOrElse(visibleIndex) { 0 }
        val lineText = textBuffer.getLine(docLine)
        val firstVisual = map.firstVisualLineByVisibleIndex.getOrElse(visibleIndex) { 0 }
        val segmentIndex = (safeVisual - firstVisual).coerceAtLeast(0)
        val layout = wordWrapLayoutCache.getWrapLayout(
            line = docLine,
            lineText = lineText,
            textVersion = textBuffer.version,
            wrapColumns = map.wrapColumns,
            tabSize = config.tabSize
        )
        return layout.endColumnForSegment(segmentIndex).coerceIn(0, lineText.length)
    }

    internal fun isVisualLineContinuation(visualLine: Int): Boolean = visualLineStartColumn(visualLine) > 0

    internal fun isDocLineHidden(docLine: Int): Boolean = foldingManager.isDocLineHidden(docLine)

    internal fun visualLineTopInViewport(visualLine: Int): Float {
        val firstLineOffset = scrollOffsetPx - firstVisibleLine * lineHeightPx
        return (visualLine - firstVisibleLine) * lineHeightPx - firstLineOffset
    }

    fun lineTopInViewport(line: Int): Float {
        val lineCount = textBuffer.lineCount
        if (lineCount <= 0) return 0f
        val safeLine = line.coerceIn(0, lineCount - 1)
        val visualLine = visualLineForDocLine(safeLine)
        return visualLineTopInViewport(visualLine)
    }

    fun updateFocus(focused: Boolean) {
        if (isFocused == focused) return
        isFocused = focused
        emitEvent(EditorEvent.FocusChanged(focused))
    }

    fun updateMetrics(
        lineHeightPx: Float,
        charWidthPx: Float,
        viewportHeightPx: Float,
        viewportWidthPx: Float,
        contentStartXPx: Float
    ) {
        val oldX = scrollOffsetXPx
        val oldY = scrollOffsetPx
        this.lineHeightPx = lineHeightPx.coerceAtLeast(1f)
        this.charWidthPx = charWidthPx.coerceAtLeast(1f)
        this.viewportHeightPx = viewportHeightPx.coerceAtLeast(1f)
        this.viewportWidthPx = viewportWidthPx.coerceAtLeast(1f)
        this.contentStartXPx = contentStartXPx.coerceAtLeast(0f)
        pendingScaleAnchor?.let { anchor ->
            pendingScaleAnchor = null
            applyScaleAnchor(anchor)
        }
        clampScroll()
        emitScrollChangedIfNeeded(oldX = oldX, oldY = oldY)
    }

    private fun applyScaleAnchor(anchor: PendingScaleAnchor) {
        val lineCount = textBuffer.lineCount
        if (lineCount <= 0) return
        val safeOffset = anchor.charOffset.coerceIn(0, textBuffer.length)
        val pos = textBuffer.offsetToPosition(safeOffset)
        val safeLine = pos.line.coerceIn(0, lineCount - 1)
        val safeColumn = pos.column.coerceIn(0, textBuffer.getLine(safeLine).length)

        val ratio = anchor.focusYInVisualLineRatio.coerceIn(0f, 1f)
        val visualLine = visualLineForPosition(safeLine, safeColumn)
        val targetContentY = visualLine * lineHeightPx + ratio * lineHeightPx
        scrollOffsetPx = targetContentY - anchor.focusY

        // wordWrap 开启时横向滚动被禁用（maxScrollXPx=0），无需尝试做 X 锚定。
        if (config.wordWrap) {
            scrollOffsetXPx = 0f
            return
        }

        val resolver = columnXInTextPxResolver
        val xInText = resolver?.invoke(safeLine, safeColumn) ?: (safeColumn * charWidthPx)
        scrollOffsetXPx = contentStartXPx + xInText - anchor.focusX
    }

    fun scrollBy(deltaPx: Float) {
        val oldX = scrollOffsetXPx
        val oldY = scrollOffsetPx
        scrollOffsetPx = (scrollOffsetPx + deltaPx).coerceIn(0f, maxScrollPx())
        emitScrollChangedIfNeeded(oldX = oldX, oldY = oldY)
    }

    fun scrollByX(deltaPx: Float) {
        val oldX = scrollOffsetXPx
        val oldY = scrollOffsetPx
        scrollOffsetXPx = (scrollOffsetXPx + deltaPx).coerceIn(0f, maxScrollXPx())
        emitScrollChangedIfNeeded(oldX = oldX, oldY = oldY)
    }

    fun maxVerticalScrollOffsetPx(): Float = maxScrollPx()

    fun maxHorizontalScrollOffsetPx(): Float = maxScrollXPx()

    fun scrollToLine(line: Int) {
        val oldX = scrollOffsetXPx
        val oldY = scrollOffsetPx
        val visualLine = resolveVisualLineForDocLine(line)
        scrollOffsetPx = (visualLine * lineHeightPx).coerceIn(0f, maxScrollPx())
        emitScrollChangedIfNeeded(oldX = oldX, oldY = oldY)
    }

    override fun moveCursorTo(offset: Int, clearSelection: Boolean) {
        val oldCursor = cursorOffset
        val oldSelection = selectionRange
        val oldX = scrollOffsetXPx
        val oldY = scrollOffsetPx
        val safeOffset = snapOffsetToEditorUnitBoundary(
            textBuffer = textBuffer,
            offset = offset,
            preferAfter = true
        )
        val pos = textBuffer.offsetToPosition(safeOffset)
        revealLineIfFolded(pos.line)
        val clampedOffset = safeOffset.coerceIn(0, textBuffer.length)
        cursorOffset = clampedOffset
        val curPos = if (clampedOffset == safeOffset) pos else textBuffer.offsetToPosition(clampedOffset)
        ensureCursorVisible(curPos.line, curPos.column)
        if (isFocused) {
            cursorBlinkVisible = true
        }
        if (clearSelection) {
            selectionRange = null
        }
        if (oldCursor != cursorOffset) {
            emitEvent(EditorEvent.CursorMoved(oldCursor, cursorOffset))
        }
        if (oldSelection != selectionRange) {
            emitEvent(EditorEvent.SelectionChanged(selectionRange))
        }
        emitScrollChangedIfNeeded(oldX = oldX, oldY = oldY)
    }

    fun ensureCursorVisible() {
        val (line, column) = cursorLineColumn()
        ensureCursorVisible(line, column)
    }

    /**
     * 仅做“纵向”光标可见性对齐。
     *
     * 设计目标：
     * - IME 弹出/收起、窗口高度变化时，确保光标不会被遮挡
     * - 避免自动调整 X 轴导致视口在缩放/横向浏览后“吸附回弹”
     *
     * 说明：
     * - 用户主动移动光标/输入时仍会走 [moveCursorTo] → 完整的 ensureCursorVisible(X+Y)
     */
    internal fun ensureCursorVisibleVertically() {
        val oldX = scrollOffsetXPx
        val oldY = scrollOffsetPx
        val (line, column) = cursorLineColumn()
        ensureCursorVisibleVertically(line, column)
        emitScrollChangedIfNeeded(oldX = oldX, oldY = oldY)
    }

    private fun ensureCursorVisibleVertically(line: Int, column: Int) {
        val safeLine = line.coerceIn(0, (textBuffer.lineCount - 1).coerceAtLeast(0))
        val visualLine = visualLineForPosition(safeLine, column)
        val top = visualLine * lineHeightPx
        val bottom = top + lineHeightPx
        val paddingY = (lineHeightPx * 1.4f)
            .coerceAtMost(viewportHeightPx * 0.25f)
            .coerceAtLeast(0f)
        scrollOffsetPx = when {
            top < scrollOffsetPx + paddingY -> {
                top - paddingY
            }

            bottom > scrollOffsetPx + viewportHeightPx - paddingY -> {
                bottom - (viewportHeightPx - paddingY)
            }

            else -> scrollOffsetPx
        }.coerceIn(0f, maxScrollPx())
    }

    fun setCursorFromPoint(xPx: Float, yPx: Float, textStartXPx: Float) {
        val line = lineFromViewportY(yPx)
        val lineText = textBuffer.getLine(line)
        val contentX = (xPx - textStartXPx + scrollOffsetXPx).coerceAtLeast(0f)
        val fractionalColumn = contentX / charWidthPx.coerceAtLeast(1f)
        val column = fractionalColumn.toInt().coerceIn(0, lineText.length)
        val rawOffset = textBuffer.positionToOffset(line, column)
        moveCursorTo(
            snapOffsetToEditorUnitBoundary(
                textBuffer = textBuffer,
                offset = rawOffset,
                preferAfter = fractionalColumn >= column
            )
        )
    }

    fun lineFromViewportY(yPx: Float): Int {
        val visualLine = visualLineFromViewportY(yPx)
        return docLineForVisualLine(visualLine)
    }

    internal fun visualLineFromViewportY(yPx: Float): Int {
        val map = visualLineMap()
        if (map.visualLineCount <= 0) return 0
        return ((yPx + scrollOffsetPx) / lineHeightPx).toInt()
            .coerceIn(0, (map.visualLineCount - 1).coerceAtLeast(0))
    }

    fun dispatchGutterTap(line: Int) {
        // fold 优先：gutter 点击在“可折叠行”上默认触发折叠开关（更符合常见编辑器习惯），
        // bookmark 等次要标记建议通过长按或其它入口操作。
        if (gutterDecorations[line]?.foldable == true) {
            onGutterFoldToggle?.invoke(line)
            return
        }
        onGutterTap?.invoke(line)
    }

    fun dispatchGutterFoldToggle(line: Int) {
        if (gutterDecorations[line]?.foldable == true) {
            onGutterFoldToggle?.invoke(line)
            return
        }
        onGutterTap?.invoke(line)
    }

    fun dispatchGutterLongPress(line: Int) {
        onGutterLongPress?.invoke(line)
    }

    override fun insert(text: String) {
        editorInsert(this, text)
    }

    /**
     * Inserts text produced by an explicit user-input control, such as the editor symbol bar.
     * This keeps virtual controls aligned with IME and hardware-keyboard smart insertion rules.
     */
    fun insertUserInput(text: String): Boolean {
        if (text.isEmpty()) return false

        val selection = selectionRange?.takeUnless(OffsetRange::isEmpty)
        val startOffset = selection?.start ?: cursorOffset
        val endOffset = selection?.end ?: cursorOffset
        val resolved = EditorSmartReplacement.resolve(
            state = this,
            startOffset = startOffset,
            replacement = text,
            endOffset = endOffset,
        )
        val changed = editorReplaceRange(
            state = this,
            startOffset = startOffset,
            endOffset = endOffset,
            replacement = resolved.replacement,
            cursorOffsetAfterEdit = resolved.cursorOffsetAfterInsert,
        )
        return changed || resolved.cursorOffsetAfterInsert != null
    }

    override fun backspace() {
        editorBackspace(this)
    }

    override fun deleteForward() {
        editorDeleteForward(this)
    }

    fun moveLeft(extendSelection: Boolean = false) {
        if (!extendSelection) {
            val range = selectionRange
            if (range != null && !range.isEmpty) {
                moveCursorTo(range.start)
                return
            }
        }
        if (cursorOffset <= 0) return
        val step = editorUnitLengthBefore(textBuffer, cursorOffset)
        moveCursorToWithOptionalSelection(
            offset = skipFoldBackwardIfHidden(cursorOffset - step),
            extendSelection = extendSelection
        )
    }

    fun moveRight(extendSelection: Boolean = false) {
        if (!extendSelection) {
            val range = selectionRange
            if (range != null && !range.isEmpty) {
                moveCursorTo(range.end)
                return
            }
        }
        if (cursorOffset >= textBuffer.length) return
        val step = editorUnitLengthAfter(textBuffer, cursorOffset)
        moveCursorToWithOptionalSelection(
            offset = skipFoldForwardIfHidden(cursorOffset + step),
            extendSelection = extendSelection
        )
    }

    /**
     * 向右移动时跳过折叠内部隐藏行。
     * 折叠末行是虚拟可见的（光标可以停留），但折叠内部行（startLine+1..endLine-1）不可停留。
     */
    private fun skipFoldForwardIfHidden(offset: Int): Int = foldingManager.skipFoldForwardIfHidden(offset)

    /**
     * 向左移动时跳过折叠内部隐藏行。
     * 从折叠末行可见文本的起始位置再往左 → 跳回折叠起始行末尾。
     * 从折叠内部行 → 跳回折叠起始行末尾。
     */
    private fun skipFoldBackwardIfHidden(offset: Int): Int = foldingManager.skipFoldBackwardIfHidden(offset)

    fun moveUp(extendSelection: Boolean = false) {
        val pos = textBuffer.offsetToPosition(cursorOffset.coerceIn(0, textBuffer.length))
        val targetLine = (pos.line - 1).coerceAtLeast(0)
        val targetCol = pos.column.coerceAtMost(textBuffer.getLine(targetLine).length)
        moveCursorToWithOptionalSelection(
            offset = textBuffer.positionToOffset(targetLine, targetCol),
            extendSelection = extendSelection
        )
    }

    fun moveDown(extendSelection: Boolean = false) {
        val pos = textBuffer.offsetToPosition(cursorOffset.coerceIn(0, textBuffer.length))
        val targetLine = (pos.line + 1).coerceAtMost((textBuffer.lineCount - 1).coerceAtLeast(0))
        val targetCol = pos.column.coerceAtMost(textBuffer.getLine(targetLine).length)
        moveCursorToWithOptionalSelection(
            offset = textBuffer.positionToOffset(targetLine, targetCol),
            extendSelection = extendSelection
        )
    }

    private fun moveCursorToWithOptionalSelection(offset: Int, extendSelection: Boolean) {
        val safeOffset = offset.coerceIn(0, textBuffer.length)
        if (!extendSelection) {
            moveCursorTo(safeOffset)
            return
        }
        if (selectionRange == null) {
            startSelection(cursorOffset)
        }
        updateSelectionTo(safeOffset)
    }

    fun gotoLine(line: Int, column: Int = 0) {
        val lineCount = textBuffer.lineCount
        if (lineCount <= 0) {
            moveCursorTo(0)
            scrollToLine(0)
            return
        }
        val safeLine = line.coerceIn(0, lineCount - 1)
        val safeColumn = column.coerceIn(0, textBuffer.getLine(safeLine).length)
        moveCursorTo(textBuffer.positionToOffset(safeLine, safeColumn))
        scrollToLine(cursorLine)
    }

    override fun selectRange(startOffset: Int, endOffset: Int) {
        selectRangeInternal(
            startOffset = startOffset,
            endOffset = endOffset,
            ensureVisible = true
        )
    }

    /**
     * 句柄拖拽期间更新选区：
     * 由 [SelectionHandleDragCoordinator] 负责”到边缘自动滚动”，这里不要再强制 ensureCursorVisible，
     * 否则会与边缘滚动互相打架，造成跳动/卡顿。
     */
    internal fun selectRangeFromHandleDrag(startOffset: Int, endOffset: Int) {
        selectRangeInternal(
            startOffset = startOffset,
            endOffset = endOffset,
            ensureVisible = false
        )
    }

    private fun selectRangeInternal(
        startOffset: Int,
        endOffset: Int,
        ensureVisible: Boolean
    ) {
        val oldCursor = cursorOffset
        val oldSelection = selectionRange
        val oldX = scrollOffsetXPx
        val oldY = scrollOffsetPx
        val (safeStart, safeEnd) = snapSelectionToEditorUnitBoundaries(
            textBuffer = textBuffer,
            start = startOffset,
            end = endOffset
        )
        selectionRange = OffsetRange(safeStart, safeEnd)
        cursorOffset = safeEnd
        if (ensureVisible) {
            val pos = textBuffer.offsetToPosition(safeEnd)
            ensureCursorVisible(pos.line, pos.column)
        }
        if (oldCursor != cursorOffset) {
            emitEvent(EditorEvent.CursorMoved(oldCursor, cursorOffset))
        }
        if (oldSelection != selectionRange) {
            emitEvent(EditorEvent.SelectionChanged(selectionRange))
        }
        emitScrollChangedIfNeeded(oldX = oldX, oldY = oldY)
    }

    fun selectedText(): String? {
        val range = selectionRange ?: return null
        if (range.isEmpty) return null
        val start = range.start.coerceIn(0, textBuffer.length)
        val end = range.end.coerceIn(start, textBuffer.length)
        if (start >= end) return null
        return textBuffer.substring(start, end)
    }

    fun selectAll() {
        if (textBuffer.length <= 0) return
        val oldCursor = cursorOffset
        val oldSelection = selectionRange
        val endOffset = textBuffer.length
        selectionRange = OffsetRange(0, endOffset)
        cursorOffset = endOffset
        if (oldCursor != cursorOffset) {
            emitEvent(EditorEvent.CursorMoved(oldCursor, cursorOffset))
        }
        if (oldSelection != selectionRange) {
            emitEvent(EditorEvent.SelectionChanged(selectionRange))
        }
    }

    override fun replaceSelection(replacement: String): Boolean = editorReplaceSelection(this, replacement)

    override fun replaceRange(
        startOffset: Int,
        endOffset: Int,
        replacement: String
    ): Boolean = editorReplaceRange(
        state = this,
        startOffset = startOffset,
        endOffset = endOffset,
        replacement = replacement
    )

    fun clearSelection() {
        val oldSelection = selectionRange
        selectionRange = null
        if (oldSelection != selectionRange) {
            emitEvent(EditorEvent.SelectionChanged(selectionRange))
        }
    }

    fun hasWordAt(line: Int, column: Int): Boolean {
        val clampedLine = line.coerceIn(0, (textBuffer.lineCount - 1).coerceAtLeast(0))
        val lineText = textBuffer.getLine(clampedLine)
        if (lineText.isEmpty()) return false
        return findSelectableWordBounds(lineText, column) != null
    }

    fun selectWord(line: Int, column: Int): Boolean {
        val clampedLine = line.coerceIn(0, (textBuffer.lineCount - 1).coerceAtLeast(0))
        val lineText = textBuffer.getLine(clampedLine)
        if (lineText.isEmpty()) return false

        val bounds = findSelectableWordBounds(lineText, column) ?: return false

        selectRange(
            startOffset = textBuffer.positionToOffset(clampedLine, bounds.start),
            endOffset = textBuffer.positionToOffset(clampedLine, bounds.end)
        )
        return true
    }

    private fun findSelectableWordBounds(lineText: String, column: Int): WordBounds? {
        val safeColumn = column.coerceIn(0, lineText.length)
        TextScanKernel.findWordBounds(lineText, safeColumn)?.let { return it }
        val adjacentWordColumn = findWordColumnBeforeAttachedPunctuation(lineText, safeColumn) ?: return null
        return TextScanKernel.findWordBounds(lineText, adjacentWordColumn)
    }

    private fun findWordColumnBeforeAttachedPunctuation(lineText: String, column: Int): Int? {
        if (column <= 0) return null
        var probe = (column - 1).coerceIn(0, lineText.lastIndex)
        var skippedPunctuation = 0
        while (
            probe >= 0 &&
            skippedPunctuation < MAX_SELECTABLE_ATTACHED_PUNCTUATION &&
            isAttachedWordPunctuation(lineText[probe])
        ) {
            skippedPunctuation++
            probe--
        }
        if (skippedPunctuation <= 0) return null
        return if (probe >= 0 && TextScanKernel.isWordChar(lineText[probe])) probe else null
    }

    private fun isAttachedWordPunctuation(char: Char): Boolean =
        !char.isWhitespace() && !TextScanKernel.isWordChar(char)

    fun startSelection(anchorOffset: Int) {
        val oldCursor = cursorOffset
        val oldSelection = selectionRange
        val safe = snapOffsetToEditorUnitBoundary(
            textBuffer = textBuffer,
            offset = anchorOffset,
            preferAfter = true
        )
        selectionRange = OffsetRange(safe, safe)
        cursorOffset = safe
        if (isFocused) {
            cursorBlinkVisible = true
        }
        if (oldCursor != cursorOffset) {
            emitEvent(EditorEvent.CursorMoved(oldCursor, cursorOffset))
        }
        if (oldSelection != selectionRange) {
            emitEvent(EditorEvent.SelectionChanged(selectionRange))
        }
    }

    fun updateSelectionTo(offset: Int) {
        val oldCursor = cursorOffset
        val oldSelection = selectionRange
        val oldX = scrollOffsetXPx
        val oldY = scrollOffsetPx
        val current = selectionRange
        val clampedOffset = offset.coerceIn(0, textBuffer.length)
        val anchor = current?.anchor ?: cursorOffset
        val safe = snapOffsetToEditorUnitBoundary(
            textBuffer = textBuffer,
            offset = clampedOffset,
            preferAfter = clampedOffset >= anchor
        )
        selectionRange = if (current == null) {
            OffsetRange(cursorOffset, safe)
        } else {
            current.copy(caret = safe)
        }
        cursorOffset = safe
        val pos = textBuffer.offsetToPosition(safe)
        ensureCursorVisible(pos.line, pos.column)
        if (isFocused) {
            cursorBlinkVisible = true
        }
        if (oldCursor != cursorOffset) {
            emitEvent(EditorEvent.CursorMoved(oldCursor, cursorOffset))
        }
        if (oldSelection != selectionRange) {
            emitEvent(EditorEvent.SelectionChanged(selectionRange))
        }
        emitScrollChangedIfNeeded(oldX = oldX, oldY = oldY)
    }

    fun canUndo(): Boolean = textBuffer.canUndo()
    fun canRedo(): Boolean = textBuffer.canRedo()

    override fun undo(): Boolean = editorUndo(this)

    override fun redo(): Boolean = editorRedo(this)

    // 补全 + snippet 状态机委托给 [completionController]（见上方构造）。以下为瘦委托，使模块内
    // 多个 Compose 文件、交互控制器、edit-ops 与测试经 state.x() 的调用点零改动。行为与原内联实现
    // 严格一致（requestId 自增/比对、防竞态返回、缓存置位时机、snippet 偏移与占位符聚焦顺序均逐行对应）。
    suspend fun requestCompletion(triggerChar: Char? = null) =
        completionController.requestCompletion(triggerChar)

    suspend fun requestHover(position: Position = cursorPosition) =
        hoverSignatureController.requestHover(position)

    suspend fun requestSignatureHelp() = hoverSignatureController.requestSignatureHelp()

    fun refilterCompletion() = completionController.refilterCompletion()

    internal fun showInlineCompletionItems(
        items: List<EditorCompletionItem>,
        selectedIndex: Int = 0,
        query: String = "",
        requestId: Long = 0L,
        snippetChoiceActive: Boolean = items.isNotEmpty()
    ) = completionController.showInlineCompletionItems(
        items = items,
        selectedIndex = selectedIndex,
        query = query,
        requestId = requestId,
        snippetChoiceActive = snippetChoiceActive
    )

    fun applyCompletion(item: EditorCompletionItem) {
        editorApplyCompletion(this, item)
    }

    fun moveCompletionSelection(delta: Int): Boolean = completionController.moveCompletionSelection(delta)

    fun setCompletionSelectedIndex(index: Int): Boolean =
        completionController.setCompletionSelectedIndex(index)

    fun applySelectedCompletion(): Boolean = completionController.applySelectedCompletion()

    fun dismissCompletion() = completionController.dismissCompletion()

    fun dismissHover() = hoverSignatureController.dismissHover()

    fun dismissSignatureHelp() = hoverSignatureController.dismissSignatureHelp()

    fun resolveDisplayedSignatureHelpIndex(result: SignatureHelpResult?): Int =
        hoverSignatureController.resolveDisplayedSignatureHelpIndex(result)

    fun selectSignatureHelp(index: Int): Boolean = hoverSignatureController.selectSignatureHelp(index)

    fun cycleSignatureHelp(delta: Int): Boolean = hoverSignatureController.cycleSignatureHelp(delta)

    internal fun startSnippetSession(session: SnippetSession) =
        completionController.startSnippetSession(session)

    /**
     * Tab 正向跳转：前进到下一个 tabstop。
     * @return 是否消费了 Tab 键事件
     */
    fun advanceSnippet(): Boolean = completionController.advanceSnippet()

    /**
     * Shift+Tab 反向跳转：退回上一个 tabstop。
     * @return 是否消费了 Shift+Tab 键事件
     */
    fun retreatSnippet(): Boolean = completionController.retreatSnippet()

    fun cancelSnippet() = completionController.cancelSnippet()

    /**
     * 在 snippet 会话内发生编辑时，同步更新会话中所有占位符的偏移量。
     *
     * @param editOffset 编辑发生的绝对文本偏移
     * @param delta      变化量（插入为正，删除为负）
     */
    internal fun adjustSnippetOffsets(editOffset: Int, delta: Int) =
        completionController.adjustSnippetOffsets(editOffset, delta)

    internal fun updateSnippetSession(session: SnippetSession?) =
        completionController.updateSnippetSession(session)

    fun replaceAll(
        findText: String,
        replaceText: String,
        caseSensitive: Boolean = false,
        useRegex: Boolean = false
    ): Int = editorReplaceAll(
        state = this,
        findText = findText,
        replaceText = replaceText,
        caseSensitive = caseSensitive,
        useRegex = useRegex
    )

    fun toggleLineComment(commentToken: String): Boolean = editorToggleLineComment(this, commentToken)

    private var cachedMaxScrollPxVersion = -1L
    private var cachedMaxScrollPxLineHeight = 0f
    private var cachedMaxScrollPxViewportH = 0f
    private var cachedMaxScrollPxVisualLineCount = -1
    private var cachedMaxScrollPxValue = 0f

    private fun maxScrollPx(): Float {
        val version = textBuffer.version
        val lh = lineHeightPx
        val vh = viewportHeightPx
        val visualLines = visualLineCount()
        if (
            version == cachedMaxScrollPxVersion &&
            lh == cachedMaxScrollPxLineHeight &&
            vh == cachedMaxScrollPxViewportH &&
            visualLines == cachedMaxScrollPxVisualLineCount
        ) {
            return cachedMaxScrollPxValue
        }
        val totalHeight = visualLines * lh
        val bottomPadding = vh * 0.5f
        val result = (totalHeight - vh + bottomPadding).coerceAtLeast(0f)
        cachedMaxScrollPxVersion = version
        cachedMaxScrollPxLineHeight = lh
        cachedMaxScrollPxViewportH = vh
        cachedMaxScrollPxVisualLineCount = visualLines
        cachedMaxScrollPxValue = result
        return result
    }

    // 横向最大滚动上界委托给 [EditorMaxLineWidthTracker]：该跟踪器维护“每行视觉列数快照 +
    // 当前最大列数”，行为与原内联实现严格一致（见该类文档说明的两处刻意保留现状）。
    private fun maxScrollXPx(): Float = maxLineWidthTracker.maxScrollXPx()

    private fun invalidateWidthSnapshot() {
        maxLineWidthTracker.invalidateWidthSnapshot()
    }

    private fun applyWidthSnapshotChange(change: TextChange, currentVersion: Long) {
        maxLineWidthTracker.applyWidthSnapshotChange(change, currentVersion)
    }

    private fun clampScroll() {
        scrollOffsetPx = scrollOffsetPx.coerceIn(0f, maxScrollPx())
        scrollOffsetXPx = scrollOffsetXPx.coerceIn(0f, maxScrollXPx())
    }

    private fun onConfigChanged(old: EditorConfig, new: EditorConfig) {
        // 同步“独立暴露”的开关（避免 config 更新后 state 字段滞后）。
        pinLineNumber = new.pinLineNumber
        useRelativeLineNumbers = new.useRelativeLineNumbers

        if (old.tabSize != new.tabSize) {
            // TabSize 影响：横向最大宽度/软换行分段/命中测试，直接失效相关快照。
            invalidateWidthSnapshot()
            visualLineMapper.invalidateVisualLineMapCache()
            wordWrapLayoutCache.invalidateAll()
        }

        if (old.wordWrap != new.wordWrap) {
            visualLineMapper.invalidateVisualLineMapCache()
            if (new.wordWrap) {
                // wordWrap 开启后横向滚动被禁用，强制回到 0 避免“坐标系漂移”。
                scrollOffsetXPx = 0f
            } else {
                // 关闭 wordWrap：清理冻结状态与缩放锚点，避免后续布局计算被旧状态污染。
                frozenWordWrapColumns = null
                pendingScaleAnchor = null
            }
        }

        if (old.codeFolding != new.codeFolding) {
            visualLineMapper.invalidateVisualLineMapCache()
        }

        clampScroll()
    }

    private fun ensureCursorVisible(line: Int, column: Int) {
        val visualLine = visualLineForPosition(line, column)
        val top = visualLine * lineHeightPx
        val bottom = top + lineHeightPx
        // 更接近常见编辑器体验：只有当光标即将出屏或贴边时才调整滚动，
        // 避免“选词/移动光标后内容整体大幅偏移”的突兀感。
        val paddingY = (lineHeightPx * 1.4f)
            .coerceAtMost(viewportHeightPx * 0.25f)
            .coerceAtLeast(0f)
        scrollOffsetPx = when {
            top < scrollOffsetPx + paddingY -> {
                top - paddingY
            }

            bottom > scrollOffsetPx + viewportHeightPx - paddingY -> {
                bottom - (viewportHeightPx - paddingY)
            }

            else -> scrollOffsetPx
        }.coerceIn(0f, maxScrollPx())

        // 开启 wordWrap 时不再进行横向滚动对齐（横向滚动被禁用）。
        if (config.wordWrap) {
            scrollOffsetXPx = 0f
            return
        }

        val paddingX = maxOf(charWidthPx * 4.0f, lineHeightPx * 0.7f)
            .coerceAtMost(viewportWidthPx * 0.25f)
            .coerceAtLeast(0f)
        val safeLine = line.coerceIn(0, (textBuffer.lineCount - 1).coerceAtLeast(0))
        val safeColumn = column.coerceAtLeast(0)
        val resolver = columnXInTextPxResolver
        val cursorLeft = resolver?.invoke(safeLine, safeColumn) ?: (safeColumn * charWidthPx)
        val nextColumn = safeColumn + 1
        val cursorRight = resolver?.invoke(safeLine, nextColumn) ?: (cursorLeft + charWidthPx)
        // 行号栏不固定时（pinLineNumber=false），允许文本在横向滚动后“吃掉”左侧行号栏区域，
        // 也就是：光标可见区域的左边界从 `textStartX(=contentStartXPx)` 变为 `0`。
        // 将其转换到“行内坐标”(cursorLeft/cursorRight)后，相当于左边界向左扩展了 contentStartXPx。
        val leftInsetInTextPx = if (pinLineNumber) 0f else contentStartXPx
        val leftBoundary = (scrollOffsetXPx - leftInsetInTextPx) + paddingX
        val rightBoundary = (scrollOffsetXPx + viewportWidthPx) - paddingX
        scrollOffsetXPx = when {
            cursorLeft < leftBoundary -> {
                cursorLeft + leftInsetInTextPx - paddingX
            }

            cursorRight > rightBoundary -> {
                cursorRight - (viewportWidthPx - paddingX)
            }

            else -> scrollOffsetXPx
        }.coerceIn(0f, maxScrollXPx())
    }

    // 代码折叠（按行隐藏）委托给 [EditorFoldingManager]：保留以下瘦委托，使本类高层方法
    // （moveCursorTo / moveLeft·moveRight 的折叠跳过等）与模块内 16+ 文件经 state.x() 的调用点
    // 零改动。行为与原内联实现严格一致。
    fun setFoldRegions(regions: List<FoldRegion>, documentVersion: Long) =
        foldingManager.setFoldRegions(regions, documentVersion)

    fun clearFoldRegions() = foldingManager.clearFoldRegions()

    fun toggleFoldAtLine(line: Int) = foldingManager.toggleFoldAtLine(line)

    internal fun isFoldCollapsedAtLine(line: Int): Boolean = foldingManager.isFoldCollapsedAtLine(line)

    internal fun isFoldingDataValid(): Boolean = foldingManager.isFoldingDataValid()

    internal fun isCollapsedFoldStart(line: Int): Boolean = foldingManager.isCollapsedFoldStart(line)

    internal fun collapsedFoldEndLine(startLine: Int): Int = foldingManager.collapsedFoldEndLine(startLine)

    internal fun isFoldEndLineVirtuallyVisible(docLine: Int): Boolean =
        foldingManager.isFoldEndLineVirtuallyVisible(docLine)

    internal fun foldOwnerForEndLine(docLine: Int): Int = foldingManager.foldOwnerForEndLine(docLine)

    internal fun markFoldAsBroken(startLine: Int) = foldingManager.markFoldAsBroken(startLine)

    internal fun foldOwnerForHiddenLine(docLine: Int): Int = foldingManager.foldOwnerForHiddenLine(docLine)

    internal fun hasHiddenDiagnosticsInFold(startLine: Int): Boolean =
        foldingManager.hasHiddenDiagnosticsInFold(startLine)

    private fun revealLineIfFolded(docLine: Int) = foldingManager.revealLineIfFolded(docLine)

    private fun resolveVisualLineForDocLine(docLine: Int): Int = visualLineForDocLine(docLine)

    internal fun visualLineCount(): Int = visualLineMap().visualLineCount.coerceAtLeast(0)

    // 视觉行映射（folding + wordWrap）委托给 [EditorVisualLineMapper]：保留以下瘦委托，
    // 使 EditorState 内的高层方法（docLineForVisualLine / visualLineForPosition / 渲染口径等）
    // 调用点零改动。行为与原内联实现严格一致。
    private fun visualLineMap(): EditorVisualLineMapper.VisualLineMap = visualLineMapper.visualLineMap()

    private fun resolveVisibleIndexForVisualLine(
        map: EditorVisualLineMapper.VisualLineMap,
        visualLine: Int
    ): Int = visualLineMapper.resolveVisibleIndexForVisualLine(map, visualLine)

    private fun resolveVisibleIndexForDocLine(docLine: Int): Int =
        visualLineMapper.resolveVisibleIndexForDocLine(docLine)

    private fun applyTextChangeToDocSegmentCounts(change: TextChange, newVersion: Long) {
        visualLineMapper.applyTextChangeToDocSegmentCounts(change, newVersion)
    }

    private fun lineVisualColumnsForWidth(lineText: String): Int = TextScanKernel.measureVisualColumns(lineText, config.tabSize)

    internal fun isWordChar(c: Char): Boolean = TextScanKernel.isWordChar(c)

    internal fun completionQueryFromCursor(): String {
        val offset = cursorOffset.coerceIn(0, textBuffer.length)
        val position = textBuffer.offsetToPosition(offset)
        val lineText = textBuffer.getLine(position.line)
        val start = TextScanKernel.findWordPrefixStart(lineText, position.column)
        if (start >= position.column) return ""
        return lineText.substring(start, position.column)
    }

    private inline fun <T> traceIfSlow(operation: String, block: () -> T): T {
        val startNs = System.nanoTime()
        val result = block()
        val durationMs = (System.nanoTime() - startNs) / 1_000_000L
        if (durationMs > SLOW_OPERATION_THRESHOLD_MS) {
            val now = System.nanoTime() / 1_000_000L
            if (now - lastSlowOperationLogAtMs >= SLOW_OPERATION_LOG_INTERVAL_MS) {
                lastSlowOperationLogAtMs = now
                Timber.tag("EditorPerf").w(
                    "Slow %s: %dms, cursorOffset=%d, lines=%d, length=%d",
                    operation,
                    durationMs,
                    cursorOffset,
                    textBuffer.lineCount,
                    textBuffer.length
                )
            }
        }
        return result
    }

    internal fun <T> traceSlowOperation(operation: String, block: () -> T): T = traceIfSlow(operation, block)

    internal fun emitTextChanged(reason: String) {
        textVersion = textBuffer.version
        emitEvent(
            EditorEvent.TextChanged(
                reason = reason,
                version = textBuffer.version,
                length = textBuffer.length
            )
        )
    }

    fun applyTextBufferChange(change: TextChange) {
        val currentVersion = textBuffer.version
        textVersion = currentVersion
        applyTextChangeToSemanticTokens(change)
        foldingManager.adjustFoldRegionsAfterTextChange(change, currentVersion)
        applyWidthSnapshotChange(change, currentVersion)
        wordWrapLayoutCache.applyTextChange(change, currentVersion)
        // segment count 与完整 wrap layout 分开维护；这里只基于当前文本重算编辑窗内的行。
        applyTextChangeToDocSegmentCounts(change, currentVersion)
        normalizeInteractionOffsetsAfterTextChange()
    }

    private fun normalizeInteractionOffsetsAfterTextChange() {
        val range = selectionRange
        if (range != null) {
            val (anchor, caret) = snapSelectionToEditorUnitBoundaries(
                textBuffer = textBuffer,
                start = range.anchor,
                end = range.caret
            )
            val normalizedRange = OffsetRange(anchor = anchor, caret = caret)
            if (selectionRange != normalizedRange) selectionRange = normalizedRange
            if (cursorOffset != caret) cursorOffset = caret
            return
        }

        val normalizedCursor = snapOffsetToEditorUnitBoundary(
            textBuffer = textBuffer,
            offset = cursorOffset,
            preferAfter = true
        )
        if (cursorOffset != normalizedCursor) cursorOffset = normalizedCursor
    }

    internal fun emitEvent(event: EditorEvent) {
        _events.tryEmit(event)
    }

    private fun emitScrollChangedIfNeeded(oldX: Float, oldY: Float) {
        if (oldX == scrollOffsetXPx && oldY == scrollOffsetPx) return
        emitEvent(
            EditorEvent.ScrollChanged(
                offsetX = scrollOffsetXPx,
                offsetY = scrollOffsetPx
            )
        )
    }
}

private const val MAX_SELECTABLE_ATTACHED_PUNCTUATION = 4
