package com.wuxianggujun.tinaide.core.editorview

/**
 * LSP/TextMate snippet 语法解析器。
 *
 * 支持：
 * - `${1:name}`   带默认值的占位符
 * - `${1}`        空占位符
 * - `$1`          简写占位符
 * - `$0`          最终光标位（排最后）
 * - `${1|a,b,c|}` Choice snippet（取第一项作为展开文本，保留选项列表）
 * - `\$`、`\}`、`\\` 转义字符
 *
 * 暂不支持：嵌套占位符、变量替换（`$TM_FILENAME` 等）。
 */
internal data class ParsedSnippet(
    val expandedText: String,
    /**
     * 按 tabstopIndex 升序排列（$0 排最后）的占位符列表。
     *
     * 同一 tabstopIndex 可能对应多个位置（e.g. `${1:name}…${1:name}` 出现两次），
     * 它们共享同一 tabstopIndex，排在连续的分组里以支持同步编辑。
     */
    val placeholders: List<SnippetPlaceholderInfo>
)

internal data class SnippetPlaceholderInfo(
    val tabstopIndex: Int,
    val offsetInText: Int,
    val length: Int,
    /** Choice snippet 的选项列表，非 choice 时为 null */
    val choices: List<String>? = null
)

internal fun parseSnippet(snippet: String): ParsedSnippet {
    val textBuilder = StringBuilder()
    val placeholders = mutableListOf<SnippetPlaceholderInfo>()
    var i = 0
    val len = snippet.length
    val pendingText = StringBuilder()

    fun flushText() {
        if (pendingText.isNotEmpty()) {
            textBuilder.append(pendingText)
            pendingText.clear()
        }
    }

    while (i < len) {
        val ch = snippet[i]

        // 转义处理：\$、\}、\\
        if (ch == '\\' && i + 1 < len) {
            val next = snippet[i + 1]
            if (next == '$' || next == '}' || next == '\\') {
                pendingText.append(next)
                i += 2
                continue
            }
        }

        // ${...} 格式
        if (ch == '$' && i + 1 < len && snippet[i + 1] == '{') {
            val closeBrace = snippet.indexOf('}', i + 2)
            if (closeBrace > i + 2) {
                val body = snippet.substring(i + 2, closeBrace)

                // Choice snippet：${1|one,two,three|}
                val choiceMatch = parseChoiceBody(body)
                if (choiceMatch != null) {
                    val (index, choices) = choiceMatch
                    flushText()
                    val defaultValue = choices.firstOrNull().orEmpty()
                    placeholders.add(
                        SnippetPlaceholderInfo(
                            tabstopIndex = index,
                            offsetInText = textBuilder.length,
                            length = defaultValue.length,
                            choices = choices
                        )
                    )
                    textBuilder.append(defaultValue)
                    i = closeBrace + 1
                    continue
                }

                val colonPos = body.indexOf(':')
                if (colonPos >= 0) {
                    // ${1:name}
                    val index = body.substring(0, colonPos).toIntOrNull()
                    val defaultValue = body.substring(colonPos + 1)
                    if (index != null) {
                        flushText()
                        placeholders.add(
                            SnippetPlaceholderInfo(
                                tabstopIndex = index,
                                offsetInText = textBuilder.length,
                                length = defaultValue.length
                            )
                        )
                        textBuilder.append(defaultValue)
                        i = closeBrace + 1
                        continue
                    }
                    // 无法解析，当文本处理
                    pendingText.append(snippet, i, closeBrace + 1)
                    i = closeBrace + 1
                    continue
                } else {
                    // ${1}
                    val index = body.toIntOrNull()
                    if (index != null) {
                        flushText()
                        placeholders.add(
                            SnippetPlaceholderInfo(
                                tabstopIndex = index,
                                offsetInText = textBuilder.length,
                                length = 0
                            )
                        )
                        i = closeBrace + 1
                        continue
                    }
                    pendingText.append(snippet, i, closeBrace + 1)
                    i = closeBrace + 1
                    continue
                }
            }
        }

        // $N 简写格式（含 $0）
        if (ch == '$' && i + 1 < len && snippet[i + 1].isDigit()) {
            flushText()
            var numEnd = i + 1
            while (numEnd < len && snippet[numEnd].isDigit()) numEnd++
            val index = snippet.substring(i + 1, numEnd).toIntOrNull()
            if (index != null) {
                placeholders.add(
                    SnippetPlaceholderInfo(
                        tabstopIndex = index,
                        offsetInText = textBuilder.length,
                        length = 0
                    )
                )
            }
            i = numEnd
            continue
        }

        pendingText.append(ch)
        i++
    }
    flushText()

    // 按 tabstopIndex 升序排列，$0 排最后；同 index 保持原始出现顺序
    val sorted = placeholders.sortedWith(
        compareBy<SnippetPlaceholderInfo> { if (it.tabstopIndex == 0) Int.MAX_VALUE else it.tabstopIndex }
            .thenBy { it.offsetInText }
    )

    return ParsedSnippet(
        expandedText = textBuilder.toString(),
        placeholders = sorted
    )
}

/**
 * 解析 choice snippet 体：`1|one,two,three|`
 * 返回 (tabstopIndex, choices) 或 null（格式不匹配）
 */
private fun parseChoiceBody(body: String): Pair<Int, List<String>>? {
    val pipeFirst = body.indexOf('|')
    if (pipeFirst < 1) return null
    val pipeLast = body.lastIndexOf('|')
    if (pipeLast <= pipeFirst) return null
    val index = body.substring(0, pipeFirst).toIntOrNull() ?: return null
    val choiceStr = body.substring(pipeFirst + 1, pipeLast)
    val choices = choiceStr.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (choices.isEmpty()) return null
    return index to choices
}
