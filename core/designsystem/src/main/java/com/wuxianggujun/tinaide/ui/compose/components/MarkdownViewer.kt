package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import com.wuxianggujun.tinaide.core.i18n.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.LeafASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser
import timber.log.Timber

private val flavour by lazy { GFMFlavourDescriptor(useSafeLinks = true) }
private val parser by lazy { MarkdownParser(flavour) }

/**
 * Markdown 渲染组件（通用）
 *
 * 用于教程、帮助页面等通用 Markdown 内容展示。
 * AST 解析在后台线程执行，不阻塞 UI。
 *
 * @param markdown Markdown 文本内容
 * @param modifier Compose Modifier
 */
@Composable
fun MarkdownViewer(
    markdown: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
    onLinkClick: ((String) -> Unit)? = null,
    onCodeCopy: ((String) -> Unit)? = null,
) {
    var (data, setData) = remember {
        val ast = parser.buildMarkdownTreeFromString(markdown)
        mutableStateOf(markdown to ast, referentialEqualityPolicy())
    }

    val updatedContent by rememberUpdatedState(markdown)
    LaunchedEffect(Unit) {
        snapshotFlow { updatedContent }
            .distinctUntilChanged()
            .catch { Timber.tag("MarkdownViewer").w(it, "Failed to parse markdown content") }
            .collectLatest { text ->
                val parsed = withContext(Dispatchers.Default) {
                    text to parser.buildMarkdownTreeFromString(text)
                }
                setData(parsed)
            }
    }

    val (parsedContent, astTree) = data
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(contentPadding)
    ) {
        astTree.children.fastForEach { child ->
            ViewerNode(
                node = child,
                content = parsedContent,
                onLinkClick = onLinkClick,
                onCodeCopy = onCodeCopy
            )
        }
    }
}

// ── AST 节点渲染 ──────────────────────────────────────────

@Composable
private fun ViewerNode(
    node: ASTNode,
    content: String,
    modifier: Modifier = Modifier,
    listLevel: Int = 0,
    onLinkClick: ((String) -> Unit)? = null,
    onCodeCopy: ((String) -> Unit)? = null,
) {
    when (node.type) {
        MarkdownElementTypes.MARKDOWN_FILE -> {
            node.children.fastForEach {
                ViewerNode(it, content, modifier, listLevel, onLinkClick, onCodeCopy)
            }
        }

        MarkdownElementTypes.PARAGRAPH -> {
            ViewerParagraph(node, content, modifier, onLinkClick = onLinkClick)
        }

        MarkdownElementTypes.ATX_1,
        MarkdownElementTypes.ATX_2,
        MarkdownElementTypes.ATX_3,
        MarkdownElementTypes.ATX_4,
        MarkdownElementTypes.ATX_5,
        MarkdownElementTypes.ATX_6 -> {
            val style = when (node.type) {
                MarkdownElementTypes.ATX_1 -> TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp)
                MarkdownElementTypes.ATX_2 -> TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp)
                MarkdownElementTypes.ATX_3 -> TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp)
                MarkdownElementTypes.ATX_4 -> TextStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp)
                MarkdownElementTypes.ATX_5 -> TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp)
                else -> TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            ProvideTextStyle(style) {
                node.children.fastForEach { child ->
                    if (child.type == MarkdownTokenTypes.ATX_CONTENT) {
                        ViewerParagraph(child, content, modifier.padding(vertical = 8.dp), trim = true)
                    }
                }
            }
        }

        MarkdownElementTypes.CODE_FENCE -> {
            val language = node.children
                .firstOrNull { it.type == MarkdownTokenTypes.FENCE_LANG }
                ?.getTextInNode(content)?.toString()?.trim()
            val contentStartIdx = node.children.indexOfFirst { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT }
            val codeText = if (contentStartIdx != -1) {
                val eol = node.children.subList(0, contentStartIdx)
                    .findLast { it.type == MarkdownTokenTypes.EOL }
                val start = eol?.endOffset ?: node.children[contentStartIdx].startOffset
                val end = node.children.findLast { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT }?.endOffset ?: start
                if (end > start) content.substring(start, end).trimIndent() else ""
            } else ""
            if (codeText.isNotEmpty()) {
                SimpleCodeBlock(
                    code = codeText,
                    language = language,
                    modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
                    onCopy = onCodeCopy
                )
            }
        }

        MarkdownElementTypes.CODE_BLOCK -> {
            val codeText = node.getTextInNode(content).toString().trimIndent()
            SimpleCodeBlock(
                code = codeText,
                language = null,
                modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
                onCopy = onCodeCopy
            )
        }

        MarkdownElementTypes.UNORDERED_LIST -> {
            val bullet = when (listLevel % 3) { 0 -> "•"; 1 -> "◦"; else -> "▪" }
            Column(modifier = modifier.padding(start = (listLevel * 8).dp, top = 2.dp, bottom = 2.dp)) {
                node.children.fastForEach { child ->
                    if (child.type == MarkdownElementTypes.LIST_ITEM) {
                        ViewerListItem(child, content, bullet, listLevel, onLinkClick, onCodeCopy)
                    }
                }
            }
        }

        MarkdownElementTypes.ORDERED_LIST -> {
            Column(modifier = modifier.padding(start = (listLevel * 8).dp, top = 2.dp, bottom = 2.dp)) {
                var idx = 1
                node.children.fastForEach { child ->
                    if (child.type == MarkdownElementTypes.LIST_ITEM) {
                        val num = child.children
                            .firstOrNull { it.type == MarkdownTokenTypes.LIST_NUMBER }
                            ?.getTextInNode(content)?.toString() ?: "$idx."
                        ViewerListItem(child, content, num, listLevel, onLinkClick, onCodeCopy)
                        idx++
                    }
                }
            }
        }

        MarkdownElementTypes.BLOCK_QUOTE -> {
            val borderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            val bgColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
            ProvideTextStyle(LocalTextStyle.current.copy(fontStyle = FontStyle.Italic)) {
                Column(
                    modifier = modifier
                        .drawWithContent {
                            drawContent()
                            drawRect(color = bgColor, size = size)
                            drawRect(color = borderColor, size = Size(8f, size.height))
                        }
                        .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 6.dp)
                ) {
                    node.children.fastForEach {
                        ViewerNode(it, content, Modifier, listLevel, onLinkClick, onCodeCopy)
                    }
                }
            }
        }

        MarkdownTokenTypes.HORIZONTAL_RULE -> {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 0.5.dp,
            )
        }

        MarkdownTokenTypes.TEXT -> {
            Text(text = node.getTextInNode(content).toString(), modifier = modifier)
        }

        else -> {
            node.children.fastForEach {
                ViewerNode(it, content, modifier, listLevel, onLinkClick, onCodeCopy)
            }
        }
    }
}

// ── 段落（AnnotatedString 富文本） ────────────────────────

@Composable
private fun ViewerParagraph(
    node: ASTNode,
    content: String,
    modifier: Modifier = Modifier,
    trim: Boolean = false,
    onLinkClick: ((String) -> Unit)? = null,
) {
    val colorScheme = MaterialTheme.colorScheme
    val codeBg = colorScheme.secondaryContainer.copy(alpha = 0.25f)
    val linkColor = colorScheme.primary

    val annotated = remember(content, node.startOffset, node.endOffset, onLinkClick) {
        buildAnnotatedString {
            node.children.fastForEach { appendInline(it, content, codeBg, linkColor, trim, onLinkClick) }
        }
    }

    val nextSibling = node.parent?.children?.let { siblings ->
        val idx = siblings.indexOf(node)
        if (idx in 0 until siblings.lastIndex) siblings[idx + 1] else null
    }
    val bottomPad = if (nextSibling != null) LocalTextStyle.current.fontSize.value.dp * 0.6f else 0.dp

    Text(
        text = annotated,
        modifier = modifier.padding(bottom = bottomPad),
        softWrap = true,
        overflow = TextOverflow.Visible,
    )
}

private fun AnnotatedString.Builder.appendInline(
    node: ASTNode,
    content: String,
    codeBg: androidx.compose.ui.graphics.Color,
    linkColor: androidx.compose.ui.graphics.Color,
    trim: Boolean = false,
    onLinkClick: ((String) -> Unit)? = null,
) {
    when {
        node.type == MarkdownTokenTypes.BLOCK_QUOTE -> {}

        node.type == GFMTokenTypes.GFM_AUTOLINK -> {
            val link = node.getTextInNode(content).toString()
            withLink(createLinkAnnotation(link, onLinkClick)) {
                withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { append(link) }
            }
        }

        node is LeafASTNode -> {
            val text = node.getTextInNode(content).toString().let { if (trim) it.trim() else it }
            append(text)
        }

        node.type == MarkdownElementTypes.EMPH -> {
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                node.children.trimMarkers(MarkdownTokenTypes.EMPH, 1).fastForEach {
                    appendInline(it, content, codeBg, linkColor, onLinkClick = onLinkClick)
                }
            }
        }

        node.type == MarkdownElementTypes.STRONG -> {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                node.children.trimMarkers(MarkdownTokenTypes.EMPH, 2).fastForEach {
                    appendInline(it, content, codeBg, linkColor, onLinkClick = onLinkClick)
                }
            }
        }

        node.type == GFMElementTypes.STRIKETHROUGH -> {
            withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                node.children.trimMarkers(GFMTokenTypes.TILDE, 2).fastForEach {
                    appendInline(it, content, codeBg, linkColor, onLinkClick = onLinkClick)
                }
            }
        }

        node.type == MarkdownElementTypes.CODE_SPAN -> {
            val code = node.getTextInNode(content).toString().trim('`')
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 0.9.em, background = codeBg)) { append(code) }
        }

        node.type == MarkdownElementTypes.INLINE_LINK -> {
            val dest = node.findRecursive(MarkdownElementTypes.LINK_DESTINATION)?.getTextInNode(content)?.toString() ?: ""
            val text = node.findRecursive(MarkdownElementTypes.LINK_TEXT)?.getTextInNode(content)?.toString()?.trim('[', ']') ?: dest
            if (dest.isNotBlank()) {
                withLink(createLinkAnnotation(dest, onLinkClick)) {
                    withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { append(text) }
                }
            } else append(text)
        }

        node.type == MarkdownElementTypes.AUTOLINK -> {
            node.children.filter { it.type != MarkdownTokenTypes.LT && it.type != MarkdownTokenTypes.GT }.fastForEach {
                val url = it.getTextInNode(content).toString()
                withLink(createLinkAnnotation(url, onLinkClick)) {
                    withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { append(url) }
                }
            }
        }

        else -> node.children.fastForEach {
            appendInline(it, content, codeBg, linkColor, trim, onLinkClick)
        }
    }
}

private fun createLinkAnnotation(
    url: String,
    onLinkClick: ((String) -> Unit)?
): LinkAnnotation {
    return if (onLinkClick == null) {
        LinkAnnotation.Url(url)
    } else {
        LinkAnnotation.Clickable(
            tag = url,
            linkInteractionListener = { onLinkClick(url) }
        )
    }
}

// ── 列表项 ──────────────────────────────────────────────

@Composable
private fun ViewerListItem(
    node: ASTNode,
    content: String,
    bullet: String,
    level: Int,
    onLinkClick: ((String) -> Unit)? = null,
    onCodeCopy: ((String) -> Unit)? = null
) {
    val direct = mutableListOf<ASTNode>()
    val nested = mutableListOf<ASTNode>()
    node.children.fastForEach {
        if (it.type == MarkdownElementTypes.UNORDERED_LIST || it.type == MarkdownElementTypes.ORDERED_LIST) nested.add(it) else direct.add(it)
    }
    Column {
        if (direct.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("$bullet ", color = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    direct.fastForEach {
                        ViewerNode(it, content, Modifier, level, onLinkClick, onCodeCopy)
                    }
                }
            }
        }
        nested.fastForEach { ViewerNode(it, content, Modifier, level + 1, onLinkClick, onCodeCopy) }
    }
}

// ── 简易代码块（不依赖 tree-sitter） ───────────────────────

@Composable
private fun SimpleCodeBlock(
    code: String,
    language: String?,
    modifier: Modifier = Modifier,
    onCopy: ((String) -> Unit)? = null
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier,
    ) {
        Column {
            if (language != null || onCopy != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = language.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (onCopy != null) {
                        TextButton(
                            onClick = { onCopy(code) }
                        ) {
                            Text(stringResource(Strings.action_copy))
                        }
                    }
                }
            }
            Text(
                text = code,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            )
        }
    }
}

// ── 辅助 ──────────────────────────────────────────────────

private fun ASTNode.findRecursive(vararg types: org.intellij.markdown.IElementType): ASTNode? {
    if (type in types) return this
    for (child in children) { child.findRecursive(*types)?.let { return it } }
    return null
}

private fun List<ASTNode>.trimMarkers(type: org.intellij.markdown.IElementType, count: Int): List<ASTNode> {
    if (isEmpty() || count <= 0) return this
    var start = 0; var end = size
    var t = 0; while (start < end && t < count && this[start].type == type) { start++; t++ }
    t = 0; while (end > start && t < count && this[end - 1].type == type) { end--; t++ }
    return subList(start, end)
}
