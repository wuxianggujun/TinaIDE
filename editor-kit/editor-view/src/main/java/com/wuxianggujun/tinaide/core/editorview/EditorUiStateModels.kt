package com.wuxianggujun.tinaide.core.editorview

import com.wuxianggujun.tinaide.core.editorlsp.SignatureHelpResult

/**
 * Editor completion / hover / signature-help UI state models.
 */

sealed interface EditorCompletionFetchResult {
    data class Success(val items: List<EditorCompletionItem>) : EditorCompletionFetchResult
    data class TransientFailure(val reason: String? = null) : EditorCompletionFetchResult
}

sealed interface CompletionUiState {
    data object Hidden : CompletionUiState
    data class Loading(
        val previousItems: List<EditorCompletionItem>,
        val query: String,
        val selectedIndex: Int,
        val requestId: Long
    ) : CompletionUiState

    data class Visible(
        val items: List<EditorCompletionItem>,
        val query: String,
        val selectedIndex: Int,
        val requestId: Long
    ) : CompletionUiState
}

sealed interface HoverUiState {
    data object Hidden : HoverUiState
    data object Loading : HoverUiState
    data class Visible(val markdown: String) : HoverUiState
}

sealed interface SignatureHelpUiState {
    data object Hidden : SignatureHelpUiState
    data class Loading(
        val previousResult: SignatureHelpResult?,
        val requestId: Long
    ) : SignatureHelpUiState

    data class Visible(
        val result: SignatureHelpResult,
        val requestId: Long
    ) : SignatureHelpUiState
}
