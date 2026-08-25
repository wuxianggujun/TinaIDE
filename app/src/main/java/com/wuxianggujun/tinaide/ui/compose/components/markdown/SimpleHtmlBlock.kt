package com.wuxianggujun.tinaide.ui.compose.components.markdown

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * 简单 HTML 块渲染
 *
 * 使用 Jsoup 解析 HTML，支持常见标签：
 * - 文本格式：<b>, <i>, <u>, <code>, <s>, <em>, <strong>
 * - 链接：<a href>
 * - 段落：<p>, <br>, <div>
 * - 标题：<h1>-<h6>
 * - 列表：<ul>, <ol>, <li>
 * - 图片：<img src>
 * - 折叠：<details>/<summary>
 * - 进度：<progress>
 *
 * 不支持的标签降级为纯文本展示。
 */
@Composable
internal fun SimpleHtmlBlock(
    html: String,
    modifier: Modifier = Modifier,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f)

    val elements = remember(html) {
        runCatching { Jsoup.parseBodyFragment(html).body() }.getOrNull()
    }

    if (elements == null) {
        Text(text = html, modifier = modifier)
        return
    }

    Column(modifier = modifier) {
        elements.childNodes().forEach { node ->
            RenderHtmlNode(node, linkColor, codeBackground)
        }
    }
}

@Composable
private fun RenderHtmlNode(
    node: Node,
    linkColor: androidx.compose.ui.graphics.Color,
    codeBackground: androidx.compose.ui.graphics.Color,
) {
    when (node) {
        is TextNode -> {
            val text = node.wholeText.takeIf { it.isNotBlank() } ?: return
            Text(text = text)
        }
        is Element -> RenderHtmlElement(node, linkColor, codeBackground)
    }
}

@Composable
private fun RenderHtmlElement(
    element: Element,
    linkColor: androidx.compose.ui.graphics.Color,
    codeBackground: androidx.compose.ui.graphics.Color,
) {
    when (element.tagName().lowercase()) {
        "p", "div" -> {
            val annotated = buildHtmlAnnotatedString(element, linkColor, codeBackground)
            if (annotated.isNotEmpty()) {
                Text(
                    text = annotated,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
        "br" -> { /* handled within AnnotatedString */ }

        "h1", "h2", "h3", "h4", "h5", "h6" -> {
            val level = element.tagName().last().digitToInt()
            val style = when (level) {
                1 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp)
                2 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp)
                3 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp)
                4 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp)
                5 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp)
                else -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Text(
                text = buildAnnotatedString {
                    withStyle(style) { append(element.text()) }
                },
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        "ul" -> {
            Column(modifier = Modifier.padding(start = 12.dp)) {
                element.children().forEach { li ->
                    if (li.tagName().lowercase() == "li") {
                        val annotated = buildHtmlAnnotatedString(li, linkColor, codeBackground)
                        Text(
                            text = buildAnnotatedString {
                                append("• ")
                                append(annotated)
                            },
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                }
            }
        }

        "ol" -> {
            Column(modifier = Modifier.padding(start = 12.dp)) {
                element.children().forEachIndexed { index, li ->
                    if (li.tagName().lowercase() == "li") {
                        val annotated = buildHtmlAnnotatedString(li, linkColor, codeBackground)
                        Text(
                            text = buildAnnotatedString {
                                append("${index + 1}. ")
                                append(annotated)
                            },
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                }
            }
        }

        "img" -> {
            val src = element.attr("src")
            val alt = element.attr("alt")
            val safeImageUrl = MarkdownUrlPolicy.safeImageUrlOrNull(src)
            if (safeImageUrl != null) {
                AsyncImage(
                    model = safeImageUrl,
                    contentDescription = alt.ifBlank { null },
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            }
        }

        "pre" -> {
            val codeElement = element.selectFirst("code")
            val code = (codeElement ?: element).wholeText()
            Text(
                text = code,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        "details" -> {
            val summary = element.selectFirst("summary")?.text()
                ?.takeIf(String::isNotBlank)
                ?: Strings.markdown_html_details.str()
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        append("▶ $summary")
                    }
                },
                modifier = Modifier.padding(vertical = 2.dp),
            )
            Column(modifier = Modifier.padding(start = 12.dp)) {
                element.childNodes()
                    .filter { it !is Element || it.tagName().lowercase() != "summary" }
                    .forEach { child ->
                        RenderHtmlNode(child, linkColor, codeBackground)
                    }
            }
        }

        "progress" -> {
            val value = element.attr("value").toFloatOrNull()
            val max = element.attr("max").toFloatOrNull() ?: 100f
            if (value != null && value.isFinite() && max.isFinite() && max > 0f) {
                val percentage = (value / max * 100f).coerceIn(0f, 100f).toInt()
                Text(
                    text = Strings.markdown_html_progress.str(percentage),
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }

        "table" -> {
            // 简化 HTML 表格 → 纯文本
            val rows = element.select("tr")
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                rows.forEach { tr ->
                    val cells = tr.select("th, td").map { it.text() }
                    Text(
                        text = cells.joinToString(" | "),
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
        }

        else -> {
            // 未知标签 → 尝试提取文本内容
            val annotated = buildHtmlAnnotatedString(element, linkColor, codeBackground)
            if (annotated.isNotEmpty()) {
                Text(text = annotated)
            }
        }
    }
}

// ── HTML → AnnotatedString ──────────────────────────────────

private fun buildHtmlAnnotatedString(
    element: Element,
    linkColor: androidx.compose.ui.graphics.Color,
    codeBackground: androidx.compose.ui.graphics.Color,
): AnnotatedString = buildAnnotatedString {
    element.childNodes().forEach { node ->
        appendHtmlNode(node, linkColor, codeBackground)
    }
}

private fun AnnotatedString.Builder.appendHtmlNode(
    node: Node,
    linkColor: androidx.compose.ui.graphics.Color,
    codeBackground: androidx.compose.ui.graphics.Color,
) {
    when (node) {
        is TextNode -> append(node.wholeText)
        is Element -> appendHtmlElement(node, linkColor, codeBackground)
    }
}

private fun AnnotatedString.Builder.appendHtmlElement(
    element: Element,
    linkColor: androidx.compose.ui.graphics.Color,
    codeBackground: androidx.compose.ui.graphics.Color,
) {
    when (element.tagName().lowercase()) {
        "b", "strong" -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            element.childNodes().forEach { appendHtmlNode(it, linkColor, codeBackground) }
        }
        "i", "em" -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
            element.childNodes().forEach { appendHtmlNode(it, linkColor, codeBackground) }
        }
        "u" -> withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
            element.childNodes().forEach { appendHtmlNode(it, linkColor, codeBackground) }
        }
        "s", "del", "strike" -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
            element.childNodes().forEach { appendHtmlNode(it, linkColor, codeBackground) }
        }
        "code" -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) {
            append(element.wholeText())
        }
        "a" -> {
            val href = element.attr("href")
            val safeUrl = MarkdownUrlPolicy.safeLinkUrlOrNull(href)
            if (safeUrl != null) {
                withLink(LinkAnnotation.Url(safeUrl)) {
                    withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                        element.childNodes().forEach { appendHtmlNode(it, linkColor, codeBackground) }
                    }
                }
            } else {
                element.childNodes().forEach { appendHtmlNode(it, linkColor, codeBackground) }
            }
        }
        "br" -> append("\n")
        "span" -> {
            element.childNodes().forEach { appendHtmlNode(it, linkColor, codeBackground) }
        }
        else -> {
            element.childNodes().forEach { appendHtmlNode(it, linkColor, codeBackground) }
        }
    }
}
