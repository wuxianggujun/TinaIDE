package com.wuxianggujun.tinaide.ui.compose.components.markdown

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.util.fastForEach
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import timber.log.Timber

/** GFM 解析器全局单例，避免重复创建 */
private val flavour by lazy { GFMFlavourDescriptor(useSafeLinks = true) }
private val parser by lazy { MarkdownParser(flavour) }

// ── LaTeX 预处理 ──────────────────────────────────────────

private val INLINE_LATEX_REGEX = Regex("\\\\\\((.+?)\\\\\\)")
private val BLOCK_LATEX_REGEX = Regex("\\\\\\[(.+?)\\\\\\]", RegexOption.DOT_MATCHES_ALL)
private val CODE_BLOCK_REGEX = Regex("```[\\s\\S]*?```|`[^`\n]*`", RegexOption.DOT_MATCHES_ALL)

/**
 * 预处理 Markdown 内容：
 * - 将 \(...\) → $...$ 和 \[...\] → $$...$$ （跳过代码块内的）
 */
private fun preProcess(content: String): String {
    val codeRanges = CODE_BLOCK_REGEX.findAll(content).map { it.range }.toList()
    fun inCode(pos: Int) = codeRanges.any { pos in it }

    var result = INLINE_LATEX_REGEX.replace(content) { m ->
        if (inCode(m.range.first)) m.value else "$" + m.groupValues[1] + "$"
    }
    result = BLOCK_LATEX_REGEX.replace(result) { m ->
        if (inCode(m.range.first)) m.value else "$$" + m.groupValues[1] + "$$"
    }
    return result
}

/**
 * 高性能 Markdown 渲染组件
 *
 * 核心优化：
 * - AST 解析在后台线程（Dispatchers.Default）执行，不阻塞 UI
 * - snapshotFlow + mapLatest 自动取消过时的解析任务（天然防抖）
 * - referentialEqualityPolicy 防止引用不同但内容相同时的无意义重组
 * - 段落使用 AnnotatedString 单次构建，单个 Text() 渲染
 *
 * @param content Markdown 文本
 * @param modifier Compose Modifier
 * @param style 基础文本样式
 * @param onCodeCopy 代码块"复制"回调
 * @param onCodeInsert 代码块"插入到编辑器"回调
 */
@Composable
fun MarkdownBlock(
    content: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    onCodeCopy: ((String) -> Unit)? = null,
    onCodeInsert: ((String) -> Unit)? = null,
) {
    // 首帧同步解析，避免空白闪烁
    var (data, setData) = remember {
        val preprocessed = preProcess(content)
        val ast = parser.buildMarkdownTreeFromString(preprocessed)
        mutableStateOf(
            value = preprocessed to ast,
            policy = referentialEqualityPolicy(),
        )
    }

    // 后续 content 变化在后台线程异步解析
    val updatedContent by rememberUpdatedState(content)
    LaunchedEffect(Unit) {
        snapshotFlow { updatedContent }
            .distinctUntilChanged()
            .catch { Timber.tag("MarkdownBlock").w(it, "Failed to parse markdown block") }
            .collectLatest { text ->
                val parsed = withContext(Dispatchers.Default) {
                    val preprocessed = preProcess(text)
                    val ast = parser.buildMarkdownTreeFromString(preprocessed)
                    preprocessed to ast
                }
                setData(parsed)
            }
    }

    val (parsedContent, astTree) = data
    ProvideTextStyle(style) {
        Column(modifier = modifier) {
            astTree.children.fastForEach { child ->
                MarkdownNode(
                    node = child,
                    content = parsedContent,
                    onCodeCopy = onCodeCopy,
                    onCodeInsert = onCodeInsert,
                )
            }
        }
    }
}
