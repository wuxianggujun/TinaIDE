package com.wuxianggujun.tinaide.core.editorview

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.SoftwareKeyboardController
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.textengine.Position
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

internal class EditorInteractionController(
    private val state: EditorState,
    private val coroutineScope: CoroutineScope,
    private val focusRequester: FocusRequester,
    private val keyboardController: SoftwareKeyboardController?,
    private val inputMethodManager: InputMethodManager?
) {
    private companion object {
        private const val IME_DIAG_TAG = "EditorImeDiag"
        private const val SIGNATURE_HELP_TRIGGER_DEBOUNCE_MS = 40L
    }

    private var completionRequestJob: Job? = null
    private var hoverRequestJob: Job? = null
    private var signatureHelpRequestJob: Job? = null
    private var suppressAutoSignatureHelpForTextVersion: Long? = null

    /**
     * 防止 Compose 焦点系统与 Android View 焦点系统之间形成无限递归的重入守卫。
     *
     * 递归路径: focusRequester.requestFocus() → Compose onFocusChanged →
     * requestEditorFocusAndKeyboard() → view.requestFocus() →
     * View onFocusChangeListener → state.updateFocus() → 触发重组 → 循环。
     */
    private var requestingFocus = false

    var inputHostView: EditorInputHostView? = null
        private set
    var hardwareKeyEventInterceptor: ((android.view.KeyEvent) -> Boolean)? = null
    private var activeInputConnection: EditorInputConnection? = null

    fun bindInputHostView(host: EditorInputHostView) {
        val previousHost = inputHostView
        if (previousHost !== host) {
            activeInputConnection?.closeConnection()
            activeInputConnection = null
            clearInputHostBindings(previousHost)
        }
        host.inputConnectionFactory = { outAttrs ->
            activeInputConnection?.closeConnection()
            createInputConnection(host, outAttrs).also { connection ->
                activeInputConnection = connection
            }
        }
        host.keyEventHandler = { event ->
            if (hardwareKeyEventInterceptor?.invoke(event) == true) {
                true
            } else {
                val connection = activeInputConnection ?: createInputConnection(host, EditorInfo()).also {
                    activeInputConnection = it
                }
                connection.handleKeyEvent(event)
            }
        }
        host.onInputConnectionDetached = {
            activeInputConnection?.closeConnection()
            activeInputConnection = null
        }
        inputHostView = host
    }

    fun hasActiveInputHostFocus(): Boolean {
        val view = inputHostView ?: return false
        return view.isAttachedToWindow && view.hasWindowFocus() && view.isFocused
    }

    /**
     * 请求编辑器焦点并弹出键盘。
     *
     * 焦点请求策略: 优先使用 [inputHostView]（Android View 焦点）,
     * 仅在 View 不可用时回退到 [focusRequester]（Compose 焦点）。
     * 通过 [requestingFocus] 标志防止重入，彻底阻断递归。
     */
    fun requestEditorFocusAndKeyboard() {
        if (requestingFocus) return
        requestingFocus = true
        try {
            val view = inputHostView
            if (view != null) {
                if (!view.isFocused) {
                    view.requestFocus()
                }
                inputMethodManager?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
            } else {
                focusRequester.requestFocus()
                keyboardController?.show()
            }
        } finally {
            requestingFocus = false
        }
    }

    fun requestEditorFocus() {
        if (requestingFocus) return
        requestingFocus = true
        try {
            val view = inputHostView
            if (view != null) {
                if (!view.isFocused) {
                    view.requestFocus()
                }
            } else {
                focusRequester.requestFocus()
            }
        } finally {
            requestingFocus = false
        }
    }

    fun showKeyboard() {
        val view = inputHostView
        if (view != null) {
            inputMethodManager?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        } else {
            keyboardController?.show()
        }
    }

    fun hideKeyboardAndClearHostFocus() {
        val view = inputHostView ?: return
        inputMethodManager?.hideSoftInputFromWindow(view.windowToken, 0)
        view.clearFocus()
    }

    fun restartInput() {
        val view = inputHostView ?: return
        activeInputConnection?.closeConnection()
        activeInputConnection = null
        inputMethodManager?.restartInput(view)
        logIme("restartInput")
    }

    fun prepareForExternalEdit() {
        val connection = activeInputConnection ?: return
        connection.finishComposingText()
        syncSelectionToIme()
        logIme("finishComposingText before external edit")
    }

    fun syncSelectionToIme() {
        val connection = activeInputConnection
        val (selectionStart, selectionEnd) = if (connection != null) {
            connection.updateSelectionToIme()
        } else {
            val view = inputHostView ?: return
            val manager = inputMethodManager ?: return
            imeSelectionOffsets().also { selection ->
                manager.updateSelection(view, selection.first, selection.second, -1, -1)
            }
        }
        logIme(
            "updateSelection selection=($selectionStart,$selectionEnd) " +
                "selectionLen=${abs(selectionEnd - selectionStart)}"
        )
    }

    fun notifyCursorGeometryChanged() {
        activeInputConnection?.onExternalCursorGeometryChanged()
    }

    fun requestManualCompletion() {
        dismissHover()
        coroutineScope.launch {
            state.requestCompletion(null)
        }
    }

    fun requestManualSignatureHelp() {
        dismissHover()
        cancelPendingSignatureHelpRequest()
        signatureHelpRequestJob = coroutineScope.launch {
            state.requestSignatureHelp()
        }
    }

    fun dismissCompletion() {
        cancelPendingCompletionRequest()
        state.dismissCompletion()
    }

    fun cancelPendingCompletionRequest() {
        completionRequestJob?.cancel()
        completionRequestJob = null
    }

    fun requestHover() {
        requestHoverAt(state.cursorPosition)
    }

    fun requestHoverAt(
        position: Position,
        dismissInteractivePopups: Boolean = true
    ) {
        cancelPendingHoverRequest()
        if (dismissInteractivePopups) {
            dismissCompletion()
            dismissSignatureHelp()
        }
        state.dismissHover()
        hoverRequestJob = coroutineScope.launch {
            state.requestHover(position)
        }
    }

    fun dismissHover() {
        cancelPendingHoverRequest()
        state.dismissHover()
    }

    fun dismissSignatureHelp() {
        cancelPendingSignatureHelpRequest()
        state.dismissSignatureHelp()
    }

    fun cancelPendingHoverRequest() {
        hoverRequestJob?.cancel()
        hoverRequestJob = null
    }

    fun cancelPendingSignatureHelpRequest() {
        signatureHelpRequestJob?.cancel()
        signatureHelpRequestJob = null
    }

    fun onDispose() {
        cancelPendingCompletionRequest()
        cancelPendingHoverRequest()
        cancelPendingSignatureHelpRequest()
        activeInputConnection?.closeConnection()
        activeInputConnection = null
        clearInputHostBindings(inputHostView)
        hardwareKeyEventInterceptor = null
        inputHostView = null
    }

    private fun clearInputHostBindings(host: EditorInputHostView?) {
        host ?: return
        host.inputConnectionFactory = null
        host.keyEventHandler = null
        host.onInputConnectionDetached = null
    }

    private fun scheduleCompletionRequest(trigger: Char?) {
        cancelPendingCompletionRequest()
        completionRequestJob = coroutineScope.launch {
            delay(COMPLETION_TRIGGER_DEBOUNCE_MS)
            state.requestCompletion(trigger)
        }
    }

    private fun scheduleSignatureHelpRequest() {
        cancelPendingSignatureHelpRequest()
        signatureHelpRequestJob = coroutineScope.launch {
            delay(SIGNATURE_HELP_TRIGGER_DEBOUNCE_MS)
            state.requestSignatureHelp()
        }
    }

    fun onEditorEvent(
        event: EditorEvent,
        allowAutoSignatureHelpRefresh: Boolean
    ) {
        when (event) {
            is EditorEvent.TextChanged -> {
                suppressAutoSignatureHelpForTextVersion = event.version
            }

            is EditorEvent.CursorMoved -> {
                handleSignatureHelpContextChange(allowAutoSignatureHelpRefresh)
            }

            is EditorEvent.SelectionChanged -> {
                handleSignatureHelpContextChange(allowAutoSignatureHelpRefresh)
            }

            is EditorEvent.FocusChanged -> {
                if (!event.focused) {
                    dismissSignatureHelp()
                }
            }

            is EditorEvent.ScrollChanged -> Unit
        }
    }

    private fun onImeTextInserted(insertedText: String) {
        dismissHover()
        val trigger = insertedText.lastOrNull()
        when (trigger) {
            '(', ',' -> scheduleSignatureHelpRequest()
            ')' -> dismissSignatureHelp()
            else -> Unit
        }
        val cursorOffset = state.cursorOffset.coerceIn(0, state.textBuffer.length)
        val charBeforeTrigger = if (cursorOffset >= 2) {
            state.textBuffer.charAt(cursorOffset - 2)
        } else {
            null
        }
        if (shouldRequestCompletionAfterInsert(
                insertedText,
                trigger,
                charBeforeTrigger,
                state.file?.name
            )
        ) {
            if (!isTriggerCharacter(trigger, charBeforeTrigger) &&
                state.completionUiState !is CompletionUiState.Hidden &&
                state.cachedCompletionResults.isNotEmpty()
            ) {
                val newQuery = state.completionQueryFromCursor()
                if (isCompletionPrefixExtension(state.cachedCompletionPrefix, newQuery)) {
                    cancelPendingCompletionRequest()
                    state.refilterCompletion()
                    return
                }
            }
            scheduleCompletionRequest(trigger)
        } else {
            dismissCompletion()
        }
    }

    private fun handleSignatureHelpContextChange(
        allowAutoSignatureHelpRefresh: Boolean
    ) {
        val action = resolveSignatureHelpAutoRefreshAction(
            isVisible = state.signatureHelpUiState !is SignatureHelpUiState.Hidden,
            hasSelection = state.selectionRange?.isEmpty == false,
            hasActiveContext = hasActiveSignatureHelpContext(
                textBuffer = state.textBuffer,
                cursorOffset = state.cursorOffset
            ),
            allowAutoRefresh = allowAutoSignatureHelpRefresh,
            suppressForRecentEdit = consumeAutoSignatureHelpSuppression()
        )
        when (action) {
            SignatureHelpAutoRefreshAction.Ignore -> Unit
            SignatureHelpAutoRefreshAction.Refresh -> scheduleSignatureHelpRequest()
            SignatureHelpAutoRefreshAction.Dismiss -> dismissSignatureHelp()
        }
    }

    private fun consumeAutoSignatureHelpSuppression(): Boolean {
        val suppressedVersion = suppressAutoSignatureHelpForTextVersion ?: return false
        val shouldSuppress = suppressedVersion == state.textVersion
        suppressAutoSignatureHelpForTextVersion = null
        return shouldSuppress
    }

    private fun createInputConnection(
        hostView: EditorInputHostView,
        outAttrs: EditorInfo
    ): EditorInputConnection {
        val (selectionStart, selectionEnd) = imeSelectionOffsets()
        configureCodeEditorInput(outAttrs, selectionStart, selectionEnd)
        return EditorInputConnection(
            targetView = hostView,
            state = state,
            onInsertedText = { inserted ->
                onImeTextInserted(inserted)
                syncSelectionToIme()
            },
            onNonInsertEdit = {
                dismissHover()
                dismissSignatureHelp()
                dismissCompletion()
                syncSelectionToIme()
            }
        )
    }

    private fun imeSelectionOffsets(): Pair<Int, Int> {
        val range = state.selectionRange
        if (range != null) {
            return range.anchor to range.caret
        }
        val cursor = state.cursorOffset.coerceIn(0, state.textBuffer.length)
        return cursor to cursor
    }

    private fun logIme(message: String) {
        if (!isImeDiagnosticsEnabled()) return
        Timber.tag(IME_DIAG_TAG).d(message)
    }

    private fun isImeDiagnosticsEnabled(): Boolean = runCatching { Prefs.devDiagnosticsEnabled }.getOrDefault(false)
}

internal fun configureCodeEditorInput(
    outAttrs: EditorInfo,
    selectionStart: Int,
    selectionEnd: Int
) {
    outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
        EditorInfo.IME_FLAG_NO_FULLSCREEN
    outAttrs.initialSelStart = selectionStart
    outAttrs.initialSelEnd = selectionEnd
}
