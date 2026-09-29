package com.wuxianggujun.tinaide.core.editorview

/**
 * 补全候选的过滤与相关性排序。
 *
 * 从 [EditorState] 抽出的纯计算逻辑：给定候选列表、查询前缀与大小写敏感开关，
 * 返回去重、按相关性排序、截断后的候选列表。不持有任何可变状态，
 * 唯一的外部上下文（大小写敏感）通过参数显式传入。
 *
 * 行为与抽取前完全一致，仅把原 [EditorState] 内联的 `config.completionCaseSensitive`
 * 改为由调用方显式传入 [caseSensitive]。
 */
internal fun filterCompletionItems(
    items: List<EditorCompletionItem>,
    query: String,
    caseSensitive: Boolean
): List<EditorCompletionItem> {
    if (items.isEmpty()) return emptyList()
    if (query.isBlank()) {
        return items
            .distinctBy { it.completionDedupKey() }
            .sortedBy { kindSortPriority(it.kind) }
            .take(160)
    }

    val exactMatch = ArrayList<EditorCompletionItem>(items.size)
    val exactPrefix = ArrayList<EditorCompletionItem>(items.size)
    val fuzzyPrefix = ArrayList<EditorCompletionItem>(items.size / 2)
    val contains = ArrayList<EditorCompletionItem>(items.size / 2)
    items.forEach { item ->
        val primaryCandidates = item.primaryCompletionCandidates()
        val aliasCandidates = item.aliasCompletionCandidates(caseSensitive)
        when {
            primaryCandidates.any { candidate ->
                candidate.matchesCompletionQuery(query, caseSensitive)
            } ||
                aliasCandidates.any { candidate ->
                    candidate.matchesCompletionQuery(query, caseSensitive)
                } -> {
                exactMatch.add(item)
            }

            primaryCandidates.any { candidate ->
                candidate.startsWithCompletionPrefix(query, caseSensitive)
            } -> {
                exactPrefix.add(item)
            }

            aliasCandidates.any { candidate ->
                candidate.startsWithCompletionPrefix(query, caseSensitive)
            } -> {
                fuzzyPrefix.add(item)
            }

            primaryCandidates.any { candidate ->
                candidate.containsCompletionQuery(query, caseSensitive)
            } ||
                aliasCandidates.any { candidate ->
                    candidate.containsCompletionQuery(query, caseSensitive)
                } -> {
                contains.add(item)
            }
        }
    }
    val relevanceComparator = completionComparator(query = query, caseSensitive = caseSensitive)
    return (
        exactMatch.sortedWith(relevanceComparator) +
            exactPrefix.sortedWith(relevanceComparator) +
            fuzzyPrefix.sortedWith(kindComparator) +
            contains.sortedWith(relevanceComparator)
        )
        .distinctBy { it.completionDedupKey() }
        .take(160)
}

private fun completionComparator(
    query: String,
    caseSensitive: Boolean
): Comparator<EditorCompletionItem> = compareByDescending<EditorCompletionItem> { it.label.matchesCompletionQuery(query, caseSensitive) }
    .thenByDescending { it.insertText.matchesCompletionQuery(query, caseSensitive) }
    .thenBy { completionPrefixPenalty(it, query, caseSensitive) }
    .thenBy { kindSortPriority(it.kind) }
    .thenBy { shortestCompletionLength(it, caseSensitive) }
    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
    .thenBy { it.label }

private val kindComparator = compareBy<EditorCompletionItem> { kindSortPriority(it.kind) }

private fun completionPrefixPenalty(
    item: EditorCompletionItem,
    query: String,
    caseSensitive: Boolean
): Int {
    val candidates = item.primaryCompletionCandidates() + item.aliasCompletionCandidates(caseSensitive)
    val matching = candidates.filter { candidate ->
        candidate.startsWithCompletionPrefix(query, caseSensitive)
    }
    if (matching.isEmpty()) return Int.MAX_VALUE
    return matching.minOf { candidate ->
        (candidate.length - query.length).coerceAtLeast(0)
    }
}

private fun shortestCompletionLength(
    item: EditorCompletionItem,
    caseSensitive: Boolean
): Int = (item.primaryCompletionCandidates() + item.aliasCompletionCandidates(caseSensitive)).asSequence()
    .minOfOrNull { it.length } ?: Int.MAX_VALUE

private fun String?.matchesCompletionQuery(query: String, caseSensitive: Boolean): Boolean {
    val value = this ?: return false
    return if (caseSensitive) {
        value == query
    } else {
        value.equals(query, ignoreCase = true)
    }
}

private fun String?.startsWithCompletionPrefix(query: String, caseSensitive: Boolean): Boolean {
    val value = this ?: return false
    return if (caseSensitive) {
        value.startsWith(query)
    } else {
        value.startsWith(query, ignoreCase = true)
    }
}

private fun String?.containsCompletionQuery(query: String, caseSensitive: Boolean): Boolean {
    val value = this ?: return false
    return if (caseSensitive) {
        value.contains(query)
    } else {
        value.contains(query, ignoreCase = true)
    }
}

private fun kindSortPriority(kind: EditorCompletionKind): Int = when (kind) {
    EditorCompletionKind.VARIABLE, EditorCompletionKind.FIELD,
    EditorCompletionKind.PROPERTY, EditorCompletionKind.CONSTANT -> 0
    EditorCompletionKind.FUNCTION, EditorCompletionKind.METHOD,
    EditorCompletionKind.CONSTRUCTOR -> 1
    EditorCompletionKind.CLASS, EditorCompletionKind.INTERFACE,
    EditorCompletionKind.STRUCT, EditorCompletionKind.ENUM -> 2
    EditorCompletionKind.MODULE -> 3
    EditorCompletionKind.SNIPPET -> 4
    EditorCompletionKind.KEYWORD -> 5
    else -> 6
}

private fun EditorCompletionItem.primaryCompletionCandidates(): List<String> = buildList(2) {
    add(label)
    if (insertText != label) {
        add(insertText)
    }
}

private fun EditorCompletionItem.aliasCompletionCandidates(caseSensitive: Boolean): List<String> {
    if (caseSensitive) return emptyList()
    val alias = filterText?.takeIf { it.isNotBlank() } ?: return emptyList()
    if (alias == label || alias == insertText) return emptyList()
    return listOf(alias)
}

private fun EditorCompletionItem.completionDedupKey(): String {
    val normalizedLabel = label.lowercase()
    return when (kind) {
        EditorCompletionKind.METHOD,
        EditorCompletionKind.FUNCTION,
        EditorCompletionKind.CONSTRUCTOR -> buildString {
            append(normalizedLabel)
            append('')
            append(detail.orEmpty())
            append('')
            append(insertText)
            append('')
            append(
                textEdit?.let { edit ->
                    "${edit.startLine}:${edit.startColumn}-${edit.endLine}:${edit.endColumn}:${edit.newText}"
                }.orEmpty()
            )
            append('')
            append(
                additionalTextEdits.joinToString(separator = "") { edit ->
                    "${edit.startLine}:${edit.startColumn}-${edit.endLine}:${edit.endColumn}:${edit.newText}"
                }
            )
        }

        else -> normalizedLabel
    }
}
