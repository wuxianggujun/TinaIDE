package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wuxianggujun.tinaide.core.textengine.Position
import com.wuxianggujun.tinaide.core.textengine.TextBuffer

/**
 * 补全（completion）与 snippet 会话的状态机控制器。
 *
 * 从 [EditorState] 抽出的剩余最大内聚块：补全列表/查询/选中/UiState 的 publish 状态机，以及与之
 * 双向耦合的 snippet 会话（tabstop 跳转、占位符聚焦、choice 占位符联动补全弹窗）。两块互相调用
 * （snippet 占位符聚焦会弹出 choice 补全；取消 snippet 会关闭补全；请求补全会清除 snippet choice
 * 标记），因此必须作为同一个边界整体抽出 —— 互调在类内变成普通方法调用，最干净。
 *
 * 该块依赖宿主的少量只读量与回调（光标位置/文本缓冲/补全大小写敏感开关/补全 provider，以及
 * “按光标取补全前缀、移动光标、回写选区、应用补全项”四个回调），通过 [Host] 接口注入，
 * 组合而非继承（D 依赖倒置）。
 *
 * 行为与原内联实现严格一致：requestId 自增/比对、防竞态返回时机、缓存（cachedCompletionResults/
 * Prefix）置位时机、snippet 偏移调整与占位符聚焦顺序均逐行对应原 [EditorState] 实现。
 *
 * 可观察性说明：
 * - [completionItems] / [completionSelectedIndex] / [completionQuery] / [completionUiState] /
 *   [snippetChoiceCompletionActive] / [activeSnippetSession] 仍由 `mutableStateOf` 承载。Compose
 *   快照按 StateObject 身份记录读取，与持有它的对象类无关，因此 [EditorState] 暴露的 get-only 委托
 *   属性（及模块内多个 Compose 文件经 state.x 的直接读取）仍会触发重组。
 * - [showCompletion] 为派生只读，跟随 [completionUiState] / [completionItems]。
 * - [Host.setSelectionRange] 用于把 snippet 占位符聚焦产生的选区回写到宿主真实的 selectionRange
 *   state，绝不能在本类另存副本，否则丢可观察性。
 */
internal class EditorCompletionController(
    private val host: Host
) {
    /**
     * 宿主只读视图与回调。读取宿主当前状态；通过回调请求补全数据、按光标取前缀、移动光标、回写选区、
     * 应用补全项（应用补全保留在宿主侧，避免把整个 EditorState 透传进本类）。
     */
    internal interface Host {
        /** 文本缓冲（snippet 占位符聚焦读取 substring/offsetToPosition/length）。 */
        val textBuffer: TextBuffer

        /** 当前光标位置（requestCompletion 传给 provider）。 */
        val cursorPosition: Position

        /** 补全过滤是否大小写敏感（来自 config.completionCaseSensitive）。 */
        val completionCaseSensitive: Boolean

        /** 补全数据 provider（null 表示未接线，请求直接忽略）。 */
        val onRequestCompletion: (suspend (Position, Char?) -> EditorCompletionFetchResult)?

        /** 按光标位置计算当前词前缀（保留在宿主：有独立测试且被交互控制器复用）。 */
        fun completionQueryFromCursor(): String

        /** 移动光标（snippet 占位符聚焦用）。 */
        fun moveCursorTo(offset: Int, clearSelection: Boolean)

        /** 回写宿主真实的选区 state（snippet 占位符聚焦用，不可在本类另存副本）。 */
        fun setSelectionRange(range: OffsetRange?)

        /** 应用补全项（保留在宿主：editorApplyCompletion 接收整个 EditorState）。 */
        fun applyCompletion(item: EditorCompletionItem)
    }

    var completionItems by mutableStateOf<List<EditorCompletionItem>>(emptyList())
        private set
    var completionSelectedIndex by mutableStateOf(-1)
        private set
    var completionQuery by mutableStateOf("")
        private set
    var completionUiState by mutableStateOf<CompletionUiState>(CompletionUiState.Hidden)
        private set
    val showCompletion: Boolean
        get() = when (val ui = completionUiState) {
            is CompletionUiState.Visible -> ui.items.isNotEmpty()
            is CompletionUiState.Loading -> ui.previousItems.isNotEmpty()
            CompletionUiState.Hidden -> completionItems.isNotEmpty()
        }
    private var completionRequestSeq: Long = 0L
    var activeCompletionRequestId: Long = 0L
        private set
    var cachedCompletionResults: List<EditorCompletionItem> = emptyList()
        private set
    var cachedCompletionPrefix: String = ""
        private set
    var snippetChoiceCompletionActive by mutableStateOf(false)
        private set
    var activeSnippetSession by mutableStateOf<SnippetSession?>(null)
        private set

    private fun publishCompletionLoading(
        previousItems: List<EditorCompletionItem>,
        query: String,
        selectedIndex: Int,
        requestId: Long
    ) {
        val normalizedSelectedIndex = if (previousItems.isEmpty()) {
            -1
        } else {
            selectedIndex.coerceIn(0, previousItems.lastIndex)
        }
        completionItems = previousItems
        completionQuery = query
        completionSelectedIndex = normalizedSelectedIndex
        completionUiState = CompletionUiState.Loading(
            previousItems = previousItems,
            query = query,
            selectedIndex = normalizedSelectedIndex,
            requestId = requestId
        )
    }

    private fun publishCompletionResults(
        items: List<EditorCompletionItem>,
        query: String,
        selectedIndex: Int,
        requestId: Long
    ) {
        val normalizedSelectedIndex = if (items.isEmpty()) {
            -1
        } else {
            selectedIndex.coerceIn(0, items.lastIndex)
        }
        completionQuery = query
        completionItems = items
        completionSelectedIndex = normalizedSelectedIndex
        completionUiState = if (items.isEmpty()) {
            CompletionUiState.Hidden
        } else {
            CompletionUiState.Visible(
                items = items,
                query = query,
                selectedIndex = normalizedSelectedIndex,
                requestId = requestId
            )
        }
    }

    private fun publishCompletionSelection(index: Int) {
        completionSelectedIndex = index
        completionUiState = when (val ui = completionUiState) {
            is CompletionUiState.Visible -> ui.copy(selectedIndex = index)
            is CompletionUiState.Loading -> ui.copy(selectedIndex = index)
            CompletionUiState.Hidden -> CompletionUiState.Hidden
        }
    }

    suspend fun requestCompletion(triggerChar: Char? = null) {
        val provider = host.onRequestCompletion ?: return
        val replacingSnippetChoice = snippetChoiceCompletionActive
        snippetChoiceCompletionActive = false
        val requestId = ++completionRequestSeq
        activeCompletionRequestId = requestId

        val previousItems = if (replacingSnippetChoice) {
            emptyList()
        } else {
            when (val ui = completionUiState) {
                is CompletionUiState.Visible -> ui.items
                is CompletionUiState.Loading -> ui.previousItems
                CompletionUiState.Hidden -> completionItems
            }
        }
        val previousQuery = if (replacingSnippetChoice) "" else completionQuery
        val previousSelectedLabel = if (replacingSnippetChoice) {
            null
        } else {
            completionItems
                .getOrNull(completionSelectedIndex.coerceIn(0, completionItems.lastIndex.coerceAtLeast(0)))
                ?.label
        }
        val previousSelectedIndex = if (previousItems.isEmpty()) {
            -1
        } else {
            completionSelectedIndex.coerceIn(0, previousItems.lastIndex)
        }

        publishCompletionLoading(
            previousItems = previousItems,
            query = previousQuery,
            selectedIndex = previousSelectedIndex,
            requestId = requestId
        )

        val result = provider(host.cursorPosition, triggerChar)
        if (requestId != activeCompletionRequestId) return

        when (result) {
            is EditorCompletionFetchResult.TransientFailure -> {
                val fallbackItems = previousItems.ifEmpty { completionItems }
                if (fallbackItems.isNotEmpty()) {
                    val query = host.completionQueryFromCursor()
                    val filteredFallback = filterCompletionItems(
                        items = fallbackItems,
                        query = query,
                        caseSensitive = host.completionCaseSensitive
                    )
                    val selectedIndex = if (filteredFallback.isEmpty()) {
                        -1
                    } else {
                        completionSelectedIndex.coerceIn(
                            0,
                            filteredFallback.lastIndex.coerceAtLeast(0)
                        )
                    }
                    publishCompletionResults(
                        items = filteredFallback,
                        query = query,
                        selectedIndex = selectedIndex,
                        requestId = requestId
                    )
                } else {
                    publishCompletionResults(
                        items = emptyList(),
                        query = "",
                        selectedIndex = -1,
                        requestId = requestId
                    )
                }
                return
            }

            is EditorCompletionFetchResult.Success -> {
                val query = host.completionQueryFromCursor()
                cachedCompletionResults = result.items
                cachedCompletionPrefix = query
                val filtered = filterCompletionItems(
                    items = result.items,
                    query = query,
                    caseSensitive = host.completionCaseSensitive
                )
                val selectedIndex = if (filtered.isEmpty()) {
                    -1
                } else {
                    val restoredIndex = previousSelectedLabel?.let { label ->
                        filtered.indexOfFirst { it.label == label }
                    } ?: -1
                    if (restoredIndex >= 0) restoredIndex else 0
                }
                publishCompletionResults(
                    items = filtered,
                    query = query,
                    selectedIndex = selectedIndex,
                    requestId = requestId
                )
            }
        }
    }

    fun refilterCompletion() {
        val query = host.completionQueryFromCursor()
        if (cachedCompletionResults.isEmpty()) return
        val filtered = filterCompletionItems(
            items = cachedCompletionResults,
            query = query,
            caseSensitive = host.completionCaseSensitive
        )
        publishCompletionResults(
            items = filtered,
            query = query,
            selectedIndex = if (filtered.isEmpty()) -1 else 0,
            requestId = activeCompletionRequestId
        )
    }

    fun showInlineCompletionItems(
        items: List<EditorCompletionItem>,
        selectedIndex: Int = 0,
        query: String = "",
        requestId: Long = 0L,
        snippetChoiceActive: Boolean = items.isNotEmpty()
    ) {
        val normalizedSelectedIndex = if (items.isEmpty()) {
            -1
        } else {
            selectedIndex.coerceIn(0, items.lastIndex)
        }
        snippetChoiceCompletionActive = snippetChoiceActive && items.isNotEmpty()
        activeCompletionRequestId = requestId
        cachedCompletionResults = items
        cachedCompletionPrefix = query
        publishCompletionResults(
            items = items,
            query = query,
            selectedIndex = normalizedSelectedIndex,
            requestId = requestId
        )
    }

    fun moveCompletionSelection(delta: Int): Boolean {
        if (completionItems.isEmpty() || delta == 0) return false
        val size = completionItems.size
        val current = completionSelectedIndex.coerceIn(0, size - 1)
        val next = ((current + delta) % size + size) % size
        publishCompletionSelection(next)
        return true
    }

    fun setCompletionSelectedIndex(index: Int): Boolean {
        if (completionItems.isEmpty()) return false
        if (index !in completionItems.indices) return false
        publishCompletionSelection(index)
        return true
    }

    fun applySelectedCompletion(): Boolean {
        if (completionItems.isEmpty()) return false
        val selectedIndex = completionSelectedIndex.coerceIn(0, completionItems.lastIndex)
        val selectedItem = completionItems.getOrNull(selectedIndex) ?: return false
        host.applyCompletion(selectedItem)
        return true
    }

    fun dismissCompletion() {
        snippetChoiceCompletionActive = false
        publishCompletionResults(
            items = emptyList(),
            query = "",
            selectedIndex = -1,
            requestId = activeCompletionRequestId
        )
        activeCompletionRequestId = 0L
        cachedCompletionResults = emptyList()
        cachedCompletionPrefix = ""
    }

    fun startSnippetSession(session: SnippetSession) {
        activeSnippetSession = session
        applySnippetPlaceholderFocus(session)
    }

    /**
     * Tab 正向跳转：前进到下一个 tabstop。
     * @return 是否消费了 Tab 键事件
     */
    fun advanceSnippet(): Boolean {
        val session = activeSnippetSession ?: return false
        val next = session.advance()
        if (next == null) {
            cancelSnippet()
            return true
        }
        activeSnippetSession = next
        applySnippetPlaceholderFocus(next)
        return true
    }

    /**
     * Shift+Tab 反向跳转：退回上一个 tabstop。
     * @return 是否消费了 Shift+Tab 键事件
     */
    fun retreatSnippet(): Boolean {
        val session = activeSnippetSession ?: return false
        val prev = session.retreat() ?: return false
        activeSnippetSession = prev
        applySnippetPlaceholderFocus(prev)
        return true
    }

    fun cancelSnippet() {
        activeSnippetSession = null
        if (snippetChoiceCompletionActive) {
            dismissCompletion()
        }
    }

    /**
     * 在 snippet 会话内发生编辑时，同步更新会话中所有占位符的偏移量。
     *
     * @param editOffset 编辑发生的绝对文本偏移
     * @param delta      变化量（插入为正，删除为负）
     */
    fun adjustSnippetOffsets(editOffset: Int, delta: Int) {
        val session = activeSnippetSession ?: return
        activeSnippetSession = session.adjustOffsets(editOffset, delta)
    }

    fun updateSnippetSession(session: SnippetSession?) {
        activeSnippetSession = session
    }

    /**
     * 将光标/选区定位到 [session] 当前步骤的第一个占位符位置。
     * 若当前分组无占位符（不应发生），结束会话。
     */
    private fun applySnippetPlaceholderFocus(session: SnippetSession) {
        val placeholder = session.currentPlaceholder() ?: run {
            activeSnippetSession = null
            return
        }
        val start = session.absoluteOffsetOf(placeholder)
            .coerceIn(0, host.textBuffer.length)
        val end = (start + placeholder.length).coerceIn(start, host.textBuffer.length)
        if (placeholder.length > 0) {
            host.moveCursorTo(end, clearSelection = false)
            host.setSelectionRange(OffsetRange(start, end))
        } else {
            host.moveCursorTo(start, clearSelection = true)
            host.setSelectionRange(null)
        }

        val choices = placeholder.choices
        if (choices.isNullOrEmpty()) {
            if (snippetChoiceCompletionActive) {
                dismissCompletion()
            }
            return
        }

        val currentText = host.textBuffer.substring(start, end)
        val startPosition = host.textBuffer.offsetToPosition(start)
        val endPosition = host.textBuffer.offsetToPosition(end)
        val items = choices.map { choice ->
            EditorCompletionItem(
                label = choice,
                insertText = choice,
                kind = EditorCompletionKind.VALUE,
                textEdit = EditorCompletionTextEdit(
                    startLine = startPosition.line,
                    startColumn = startPosition.column,
                    endLine = endPosition.line,
                    endColumn = endPosition.column,
                    newText = choice
                )
            )
        }
        val selectedIndex = choices.indexOf(currentText).takeIf { it >= 0 } ?: 0
        showInlineCompletionItems(
            items = items,
            selectedIndex = selectedIndex,
            query = currentText
        )
    }
}
