package com.wuxianggujun.tinaide.ui.compose.components.markdown

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import coil3.compose.AsyncImage
import com.wuxianggujun.tinaide.ui.compose.components.CodeBlock
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/**
 * AST 节点 → Compose 组件映射
 *
 * 按 node.type 分发到具体的渲染 Composable。
 * 所有内联格式（粗体/斜体/链接/代码）在 [MarkdownParagraph] 中用 AnnotatedString 渲染。
 */
@Composable
internal fun MarkdownNode(
    node: ASTNode,
    content: String,
    modifier: Modifier = Modifier,
    onCodeCopy: ((String) -> Unit)? = null,
    onCodeInsert: ((String) -> Unit)? = null,
    listLevel: Int = 0,
) {
    when (node.type) {
        // 文件根节点 — 直接遍历子节点
        MarkdownElementTypes.MARKDOWN_FILE -> {
            node.children.fastForEach { child ->
                MarkdownNode(child, content, modifier, onCodeCopy, onCodeInsert, listLevel)
            }
        }

        // 段落 — 用 AnnotatedString 单次构建富文本
        MarkdownElementTypes.PARAGRAPH -> {
            MarkdownParagraph(node = node, content = content, modifier = modifier)
        }

        // 标题 H1-H6
        MarkdownElementTypes.ATX_1,
        MarkdownElementTypes.ATX_2,
        MarkdownElementTypes.ATX_3,
        MarkdownElementTypes.ATX_4,
        MarkdownElementTypes.ATX_5,
        MarkdownElementTypes.ATX_6 -> {
            HeadingNode(node = node, content = content, modifier = modifier)
        }

        // 代码围栏 ```lang ... ```
        MarkdownElementTypes.CODE_FENCE -> {
            CodeFenceNode(node, content, onCodeCopy, onCodeInsert, modifier)
        }

        // 缩进代码块
        MarkdownElementTypes.CODE_BLOCK -> {
            val codeText = node.getTextInNode(content).toString().trimIndent()
            CodeBlock(
                code = codeText,
                language = null,
                onCopy = { onCodeCopy?.invoke(codeText) },
                onInsert = { onCodeInsert?.invoke(codeText) },
                modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }

        // 无序列表
        MarkdownElementTypes.UNORDERED_LIST -> {
            UnorderedListNode(node, content, modifier, onCodeCopy, onCodeInsert, listLevel)
        }

        // 有序列表
        MarkdownElementTypes.ORDERED_LIST -> {
            OrderedListNode(node, content, modifier, onCodeCopy, onCodeInsert, listLevel)
        }

        // 引用块
        MarkdownElementTypes.BLOCK_QUOTE -> {
            BlockQuoteNode(node, content, modifier, onCodeCopy, onCodeInsert, listLevel)
        }

        // 水平分割线
        MarkdownTokenTypes.HORIZONTAL_RULE -> {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 0.5.dp,
            )
        }

        // GFM Checkbox — 任务列表复选框
        GFMTokenTypes.CHECK_BOX -> {
            val isChecked = node.getTextInNode(content).toString().trim() == "[x]"
            Surface(
                shape = RoundedCornerShape(2.dp),
                color = if (isChecked) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                },
                modifier = modifier,
            ) {
                Box(
                    modifier = Modifier
                        .padding(2.dp)
                        .size(LocalTextStyle.current.fontSize.value.dp * 0.8f),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isChecked) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(LocalTextStyle.current.fontSize.value.dp * 0.7f),
                        )
                    }
                }
            }
        }

        // GFM 表格
        GFMElementTypes.TABLE -> {
            MarkdownTable(
                node = node,
                content = content,
                modifier = modifier.padding(vertical = 4.dp),
            )
        }

        // 块级数学公式 $$...$$
        GFMElementTypes.BLOCK_MATH -> {
            val formula = node.getTextInNode(content).toString()
            MathBlock(
                latex = formula,
                modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
        }

        // 行内数学公式（顶层出现时）
        GFMElementTypes.INLINE_MATH -> {
            val formula = node.getTextInNode(content).toString()
            MathInline(
                latex = formula,
                modifier = modifier.padding(horizontal = 1.dp),
            )
        }

        // Markdown 图片 ![alt](url)
        MarkdownElementTypes.IMAGE -> {
            val linkDest = node.findChildRecursive(MarkdownElementTypes.LINK_DESTINATION)
                ?.getTextInNode(content)?.toString() ?: ""
            val altText = node.findChildRecursive(MarkdownElementTypes.LINK_TEXT)
                ?.getTextInNode(content)?.toString()
                ?.trim('[', ']', '!') ?: ""
            val safeImageUrl = MarkdownUrlPolicy.safeImageUrlOrNull(linkDest)
            if (safeImageUrl != null) {
                AsyncImage(
                    model = safeImageUrl,
                    contentDescription = altText.ifBlank { null },
                    contentScale = ContentScale.FillWidth,
                    modifier = modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            }
        }

        // HTML 块 — Jsoup 解析渲染
        MarkdownElementTypes.HTML_BLOCK -> {
            val htmlText = node.getTextInNode(content).toString()
            SimpleHtmlBlock(
                html = htmlText,
                modifier = modifier.padding(vertical = 4.dp),
            )
        }

        // 纯文本节点
        MarkdownTokenTypes.TEXT -> {
            Text(
                text = node.getTextInNode(content).toString(),
                modifier = modifier,
            )
        }

        // 其他节点 — 递归子节点
        else -> {
            node.children.fastForEach { child ->
                MarkdownNode(child, content, modifier, onCodeCopy, onCodeInsert, listLevel)
            }
        }
    }
}

// ── 标题 ──────────────────────────────────────────────────

@Composable
private fun HeadingNode(node: ASTNode, content: String, modifier: Modifier) {
    val style = when (node.type) {
        MarkdownElementTypes.ATX_1 -> TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp)
        MarkdownElementTypes.ATX_2 -> TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp)
        MarkdownElementTypes.ATX_3 -> TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp)
        MarkdownElementTypes.ATX_4 -> TextStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp)
        MarkdownElementTypes.ATX_5 -> TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp)
        MarkdownElementTypes.ATX_6 -> TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp)
        else -> TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
    val headingPadding = when (node.type) {
        MarkdownElementTypes.ATX_1 -> 14.dp
        MarkdownElementTypes.ATX_2 -> 12.dp
        MarkdownElementTypes.ATX_3 -> 10.dp
        else -> 8.dp
    }
    ProvideTextStyle(style) {
        node.children.fastForEach { child ->
            if (child.type == MarkdownTokenTypes.ATX_CONTENT) {
                MarkdownParagraph(
                    node = child,
                    content = content,
                    modifier = modifier.padding(vertical = headingPadding),
                    trim = true,
                )
            }
        }
    }
}

// ── 代码围栏 ──────────────────────────────────────────────

@Composable
private fun CodeFenceNode(
    node: ASTNode,
    content: String,
    onCodeCopy: ((String) -> Unit)?,
    onCodeInsert: ((String) -> Unit)?,
    modifier: Modifier,
) {
    // 提取语言标识
    val language = node.children
        .firstOrNull { it.type == MarkdownTokenTypes.FENCE_LANG }
        ?.getTextInNode(content)?.toString()?.trim()

    // 提取代码内容：从第一个 EOL 之后到最后一个 CODE_FENCE_CONTENT 结束
    val contentStartIndex = node.children.indexOfFirst { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT }
    val codeText = if (contentStartIndex != -1) {
        val eolElement = node.children.subList(0, contentStartIndex)
            .findLast { it.type == MarkdownTokenTypes.EOL }
        val startOffset = eolElement?.endOffset ?: node.children[contentStartIndex].startOffset
        val endOffset = node.children
            .findLast { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT }
            ?.endOffset ?: startOffset
        if (endOffset > startOffset) {
            content.substring(startOffset, endOffset).trimIndent()
        } else {
            ""
        }
    } else {
        ""
    }

    if (codeText.isNotEmpty()) {
        if (language.equals("mermaid", ignoreCase = true)) {
            MermaidDiagram(
                code = codeText,
                onCopy = { onCodeCopy?.invoke(codeText) },
                onInsert = { onCodeInsert?.invoke(codeText) },
                modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        } else {
            CodeBlock(
                code = codeText,
                language = language,
                onCopy = { onCodeCopy?.invoke(codeText) },
                onInsert = { onCodeInsert?.invoke(codeText) },
                modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }
    }
}

// ── 无序列表 ──────────────────────────────────────────────

@Composable
private fun UnorderedListNode(
    node: ASTNode,
    content: String,
    modifier: Modifier,
    onCodeCopy: ((String) -> Unit)?,
    onCodeInsert: ((String) -> Unit)?,
    level: Int,
) {
    val bullet = when (level % 3) {
        0 -> "•"
        1 -> "◦"
        else -> "▪"
    }
    Column(modifier = modifier.padding(start = (level * 8).dp, top = 2.dp, bottom = 2.dp)) {
        node.children.fastForEach { child ->
            if (child.type == MarkdownElementTypes.LIST_ITEM) {
                ListItemNode(child, content, bullet, onCodeCopy, onCodeInsert, level)
            }
        }
    }
}

// ── 有序列表 ──────────────────────────────────────────────

@Composable
private fun OrderedListNode(
    node: ASTNode,
    content: String,
    modifier: Modifier,
    onCodeCopy: ((String) -> Unit)?,
    onCodeInsert: ((String) -> Unit)?,
    level: Int,
) {
    Column(modifier = modifier.padding(start = (level * 8).dp, top = 2.dp, bottom = 2.dp)) {
        var index = 1
        node.children.fastForEach { child ->
            if (child.type == MarkdownElementTypes.LIST_ITEM) {
                val numberText = child.children
                    .firstOrNull { it.type == MarkdownTokenTypes.LIST_NUMBER }
                    ?.getTextInNode(content)?.toString()
                    ?: "$index."
                ListItemNode(child, content, numberText, onCodeCopy, onCodeInsert, level)
                index++
            }
        }
    }
}

// ── 列表项 ──────────────────────────────────────────────

@Composable
private fun ListItemNode(
    node: ASTNode,
    content: String,
    bullet: String,
    onCodeCopy: ((String) -> Unit)?,
    onCodeInsert: ((String) -> Unit)?,
    level: Int,
) {
    // 分离直接内容和嵌套列表
    val directContent = mutableListOf<ASTNode>()
    val nestedLists = mutableListOf<ASTNode>()
    node.children.fastForEach { child ->
        when (child.type) {
            MarkdownElementTypes.UNORDERED_LIST,
            MarkdownElementTypes.ORDERED_LIST -> nestedLists.add(child)
            else -> directContent.add(child)
        }
    }

    Column {
        if (directContent.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "$bullet ",
                    color = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.weight(1f)) {
                    directContent.fastForEach { child ->
                        MarkdownNode(child, content, Modifier, onCodeCopy, onCodeInsert, level)
                    }
                }
            }
        }
        nestedLists.fastForEach { nested ->
            MarkdownNode(nested, content, Modifier, onCodeCopy, onCodeInsert, level + 1)
        }
    }
}

// ── 引用块 ──────────────────────────────────────────────

@Composable
private fun BlockQuoteNode(
    node: ASTNode,
    content: String,
    modifier: Modifier,
    onCodeCopy: ((String) -> Unit)?,
    onCodeInsert: ((String) -> Unit)?,
    listLevel: Int,
) {
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
            node.children.fastForEach { child ->
                MarkdownNode(child, content, Modifier, onCodeCopy, onCodeInsert, listLevel)
            }
        }
    }
}
