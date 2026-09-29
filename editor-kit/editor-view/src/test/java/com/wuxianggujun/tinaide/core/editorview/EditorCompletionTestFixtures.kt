package com.wuxianggujun.tinaide.core.editorview

internal fun EditorState.seedVisibleCompletion(
    items: List<EditorCompletionItem> = listOf(
        EditorCompletionItem("print"),
        EditorCompletionItem("private")
    ),
    query: String = "pri",
    selectedIndex: Int = 0,
    requestId: Long = 1L
) {
    showInlineCompletionItems(
        items = items,
        selectedIndex = selectedIndex,
        query = query,
        requestId = requestId,
        snippetChoiceActive = false
    )
}
