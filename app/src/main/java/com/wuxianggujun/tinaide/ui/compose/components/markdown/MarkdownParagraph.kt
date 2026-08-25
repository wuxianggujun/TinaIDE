package com.wuxianggujun.tinaide.ui.compose.components.markdown

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.util.fastForEach
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.LeafASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/** 匹配 HTML <br/> 标签，替换为换行符 */
private val BREAK_LINE_REGEX = Regex("(?i)<br\\s*/?>")

/**
 * Markdown 段落渲染
 *
 * 将段落内所有内联格式（粗体、斜体、链接、行内代码、删除线等）构建为
 * 单个 [AnnotatedString]，用单个 [Text] 渲染。
 * 相比每个内联元素各一个 Composable，大幅减少组合树深度和重组开销。
 */
@Composable
internal fun MarkdownParagraph(
    node: ASTNode,
    content: String,
    modifier: Modifier = Modifier,
    trim: Boolean = false,
) {
    val colorScheme = MaterialTheme.colorScheme
    val codeBackground = colorScheme.secondaryContainer.copy(alpha = 0.25f)
    val linkColor = colorScheme.primary
    val density = LocalDensity.current
    val textStyle = LocalTextStyle.current
    val fontSizePx = with(density) { textStyle.fontSize.toPx() }

    val inlineContents = remember { mutableStateMapOf<String, InlineTextContent>() }

    val annotatedString = remember(
        content,
        node.startOffset,
        node.endOffset,
        codeBackground,
        linkColor,
        trim,
        fontSizePx,
        density,
    ) {
        inlineContents.clear()
        buildAnnotatedString {
            node.children.fastForEach { child ->
                appendInlineNode(
                    child,
                    content,
                    codeBackground,
                    linkColor,
                    trim,
                    inlineContents,
                    fontSizePx,
                    density,
                )
            }
        }
    }

    val nextSibling = node.parent?.children?.let { siblings ->
        val idx = siblings.indexOf(node)
        if (idx >= 0 && idx + 1 < siblings.size) siblings[idx + 1] else null
    }
    val bottomPadding = if (nextSibling != null) {
        LocalTextStyle.current.fontSize.value.dp * 0.6f
    } else {
        0.dp
    }

    Text(
        text = annotatedString,
        modifier = modifier.padding(bottom = bottomPadding),
        softWrap = true,
        overflow = TextOverflow.Visible,
        inlineContent = inlineContents,
    )
}

// ── AnnotatedString 构建 ──────────────────────────────────

private fun AnnotatedString.Builder.appendInlineNode(
    node: ASTNode,
    content: String,
    codeBackground: androidx.compose.ui.graphics.Color,
    linkColor: androidx.compose.ui.graphics.Color,
    trim: Boolean = false,
    inlineContents: MutableMap<String, InlineTextContent>? = null,
    fontSizePx: Float = 0f,
    density: Density? = null,
) {
    when {
        // 跳过 BLOCK_QUOTE 标记
        node.type == MarkdownTokenTypes.BLOCK_QUOTE -> {}

        // GFM 自动链接
        node.type == GFMTokenTypes.GFM_AUTOLINK -> {
            val link = node.getTextInNode(content).toString()
            val safeUrl = MarkdownUrlPolicy.safeLinkUrlOrNull(link)
            if (safeUrl != null) {
                withLink(LinkAnnotation.Url(safeUrl)) {
                    withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                        append(link)
                    }
                }
            } else {
                append(link)
            }
        }

        // 行内数学公式 $...$
        node.type == GFMElementTypes.INLINE_MATH -> {
            val formula = node.getTextInNode(content).toString()
            if (inlineContents != null && density != null && fontSizePx > 0f) {
                val bounds = assumeLatexSize(formula, fontSizePx)
                if (bounds.width() > 0 && bounds.height() > 0) {
                    val id = "math_${node.startOffset}"
                    val widthSp = with(density) { bounds.width().toFloat().toSp() }
                    val heightSp = with(density) { bounds.height().toFloat().toSp() }
                    inlineContents[id] = InlineTextContent(
                        Placeholder(
                            width = widthSp,
                            height = heightSp,
                            placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                        )
                    ) {
                        MathInline(
                            latex = formula,
                            fontSize = with(density) { fontSizePx.toSp() },
                        )
                    }
                    appendInlineContent(id, LatexRenderPolicy.fallbackText(formula))
                } else {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                        append(LatexRenderPolicy.fallbackText(formula))
                    }
                }
            } else {
                append(LatexRenderPolicy.fallbackText(formula))
            }
        }

        // 叶子节点（TEXT, WHITE_SPACE, EOL 等）
        node is LeafASTNode -> {
            val text = node.getTextInNode(content).toString().let {
                if (trim) it.trim() else it
            }.replace(BREAK_LINE_REGEX, "\n")
            append(text)
        }

        // 斜体
        node.type == MarkdownElementTypes.EMPH -> {
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                node.children.trimMarkers(MarkdownTokenTypes.EMPH, 1).fastForEach {
                    appendInlineNode(it, content, codeBackground, linkColor, trim, inlineContents, fontSizePx, density)
                }
            }
        }

        // 粗体
        node.type == MarkdownElementTypes.STRONG -> {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                node.children.trimMarkers(MarkdownTokenTypes.EMPH, 2).fastForEach {
                    appendInlineNode(it, content, codeBackground, linkColor, trim, inlineContents, fontSizePx, density)
                }
            }
        }

        // 删除线
        node.type == GFMElementTypes.STRIKETHROUGH -> {
            withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                node.children.trimMarkers(GFMTokenTypes.TILDE, 2).fastForEach {
                    appendInlineNode(it, content, codeBackground, linkColor, trim, inlineContents, fontSizePx, density)
                }
            }
        }

        // 行内代码
        node.type == MarkdownElementTypes.CODE_SPAN -> {
            val code = node.getTextInNode(content).toString().trim('`')
            withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 0.9.em,
                    background = codeBackground,
                )
            ) {
                append(code)
            }
        }

        // 行内链接 [text](url)
        node.type == MarkdownElementTypes.INLINE_LINK -> {
            val linkDest = node.findChildRecursive(MarkdownElementTypes.LINK_DESTINATION)
                ?.getTextInNode(content)?.toString() ?: ""
            val linkText = node.findChildRecursive(MarkdownElementTypes.LINK_TEXT)
                ?.getTextInNode(content)?.toString()
                ?.trim('[', ']') ?: linkDest

            val safeUrl = MarkdownUrlPolicy.safeLinkUrlOrNull(linkDest)
            if (safeUrl != null) {
                withLink(LinkAnnotation.Url(safeUrl)) {
                    withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                        append(linkText)
                    }
                }
            } else {
                append(linkText)
            }
        }

        // 自动链接 <url>
        node.type == MarkdownElementTypes.AUTOLINK -> {
            val links = node.children
                .filter { it.type != MarkdownTokenTypes.LT && it.type != MarkdownTokenTypes.GT }
            links.fastForEach { linkNode ->
                val url = linkNode.getTextInNode(content).toString()
                val safeUrl = MarkdownUrlPolicy.safeLinkUrlOrNull(url)
                if (safeUrl != null) {
                    withLink(LinkAnnotation.Url(safeUrl)) {
                        withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                            append(url)
                        }
                    }
                } else {
                    append(url)
                }
            }
        }

        // 其他节点 — 递归子节点
        else -> {
            node.children.fastForEach { child ->
                appendInlineNode(child, content, codeBackground, linkColor, trim, inlineContents, fontSizePx, density)
            }
        }
    }
}

// ── 辅助函数 ──────────────────────────────────────────────

/**
 * 递归查找指定类型的子节点
 */
internal fun ASTNode.findChildRecursive(vararg types: org.intellij.markdown.IElementType): ASTNode? {
    if (this.type in types) return this
    for (child in children) {
        val result = child.findChildRecursive(*types)
        if (result != null) return result
    }
    return null
}

/**
 * 裁剪列表首尾的标记节点（如 EMPH 的 * 或 TILDE 的 ~）
 */
private fun List<ASTNode>.trimMarkers(type: org.intellij.markdown.IElementType, count: Int): List<ASTNode> {
    if (isEmpty() || count <= 0) return this
    var start = 0
    var end = size
    var trimmed = 0
    while (start < end && trimmed < count && this[start].type == type) {
        start++
        trimmed++
    }
    trimmed = 0
    while (end > start && trimmed < count && this[end - 1].type == type) {
        end--
        trimmed++
    }
    return subList(start, end)
}
