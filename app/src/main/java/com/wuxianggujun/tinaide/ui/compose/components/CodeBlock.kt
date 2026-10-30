package com.wuxianggujun.tinaide.ui.compose.components

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.CodeOff
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.wuxianggujun.tinaide.core.editorview.EditorColorScheme
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.treesitter.HighlightSpan
import com.wuxianggujun.tinaide.core.treesitter.TreeSitterHighlighter
import com.wuxianggujun.tinaide.core.treesitter.TreeSitterLanguageRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 代码块组件
 *
 * 优化策略：默认使用轻量 Compose Text 显示代码预览（最多 5 行），
 * 点击"查看代码"按钮后才创建 TreeSitter 高亮器和 AndroidView，
 * 避免大量代码块同时加载时的性能问题。
 */
@Composable
fun CodeBlock(
    code: String,
    language: String?,
    onCopy: () -> Unit,
    onInsert: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // 与主界面编辑器保持一致的配色
    val editorScheme = remember { EditorColorScheme.builtinGray() }
    val surfaceColor = editorScheme.background // 0xFF202124 深色背景
    val textColor = editorScheme.foreground // 0xFFE6E8EB 浅色前景

    // 保存文件 launcher
    val saveFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(code.toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    val lineCount = remember(code) { code.count { it == '\n' } + 1 }

    // 是否展开高亮视图（默认关闭，使用轻量文本预览）
    var isHighlightMode by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier,
        color = surfaceColor,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column {
            // 顶部栏：语言标签 + 操作按钮（使用编辑器 gutter 背景色）
            val toolbarBg = editorScheme.gutterBackground // 0xFF25272C
            val labelColor = editorScheme.lineNumberForeground // 0xFF9AA0A6
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(toolbarBg)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = language ?: "code",
                        style = MaterialTheme.typography.labelSmall,
                        color = labelColor
                    )
                    Text(
                        text = "($lineCount ${stringResource(Strings.code_block_lines)})",
                        style = MaterialTheme.typography.labelSmall,
                        color = labelColor.copy(alpha = 0.6f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // 查看/收起高亮按钮
                    IconButton(
                        onClick = { isHighlightMode = !isHighlightMode },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (isHighlightMode) Icons.Outlined.CodeOff else Icons.Outlined.Code,
                            contentDescription = stringResource(
                                if (isHighlightMode) Strings.code_block_hide else Strings.code_block_view
                            ),
                            modifier = Modifier.size(18.dp),
                            tint = if (isHighlightMode) editorScheme.syntax.keyword else labelColor
                        )
                    }
                    IconButton(
                        onClick = onCopy,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = stringResource(Strings.tina_code_block_copy),
                            modifier = Modifier.size(18.dp),
                            tint = labelColor
                        )
                    }
                    IconButton(
                        onClick = onInsert,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AddCircleOutline,
                            contentDescription = stringResource(Strings.code_block_insert),
                            modifier = Modifier.size(18.dp),
                            tint = editorScheme.syntax.keyword
                        )
                    }
                    IconButton(
                        onClick = {
                            val ext = languageToExtension(language)
                            saveFileLauncher.launch("code$ext")
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.SaveAlt,
                            contentDescription = stringResource(Strings.code_block_save),
                            modifier = Modifier.size(18.dp),
                            tint = labelColor
                        )
                    }
                }
            }

            if (isHighlightMode) {
                // 高亮模式：使用 TreeSitter + AndroidView（按需加载）
                HighlightedCodeContent(
                    code = code,
                    language = language,
                    lineCount = lineCount,
                    surfaceColor = surfaceColor,
                    textColor = textColor,
                )
            } else {
                // 预览模式：轻量 Compose Text，最多显示 5 行
                CodePreviewContent(
                    code = code,
                    lineCount = lineCount,
                    textColor = textColor,
                )
            }
        }
    }
}

/**
 * 轻量代码预览：纯 Compose Text，无 TreeSitter、无 AndroidView
 */
@Composable
private fun CodePreviewContent(
    code: String,
    lineCount: Int,
    textColor: Color,
) {
    val previewMaxLines = 5
    val previewText = remember(code) {
        val lines = code.lines()
        if (lines.size <= previewMaxLines) {
            code
        } else {
            lines.take(previewMaxLines).joinToString("\n") + "\n..."
        }
    }

    Text(
        text = previewText,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        color = textColor,
        maxLines = previewMaxLines + 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(12.dp),
    )
}

/**
 * 高亮代码内容：使用 TreeSitter 语法高亮 + AndroidView 渲染
 * 仅在用户点击"查看代码"后才会被组合，避免默认加载开销
 */
@Composable
private fun HighlightedCodeContent(
    code: String,
    language: String?,
    lineCount: Int,
    surfaceColor: Color,
    textColor: Color,
) {
    val context = LocalContext.current

    // 语法高亮器（按需创建，Composition 销毁时释放）
    val tsLanguage = remember(language) { TreeSitterLanguageRegistry.resolveLanguageName(language) }
    val highlighter = remember(tsLanguage) {
        if (tsLanguage != null) TreeSitterHighlighter.create(context, tsLanguage) else null
    }
    DisposableEffect(highlighter) {
        onDispose { highlighter?.dispose() }
    }

    val syntaxColors = remember { EditorColorScheme.builtinGray().syntax }

    // 异步高亮结果
    var highlightedText by remember(code, tsLanguage) { mutableStateOf<CharSequence?>(null) }
    LaunchedEffect(code, highlighter) {
        if (highlighter == null || code.isEmpty()) {
            highlightedText = null
            return@LaunchedEffect
        }
        highlightedText = withContext(Dispatchers.Default) {
            // 新 SyntaxHighlighter 是推模型：先同步建快照，再按行取缓存段。
            highlighter.openDocumentBlocking(code)
            val lineCount = code.count { it == '\n' } + 1
            var offset = 0
            val spans = buildList {
                code.lineSequence().forEachIndexed { line, text ->
                    highlighter.getLineSegments(line).forEach { segment ->
                        val start = offset + segment.startColumn.coerceIn(0, text.length)
                        val end = offset + segment.endColumn.coerceIn(start - offset, text.length)
                        if (end > start) add(HighlightSpan(start, end, segment.type, segment.priority))
                    }
                    offset += text.length + 1
                }
            }
            buildHighlightedText(code, spans, syntaxColors)
        }
    }

    val shouldCollapse = lineCount > 20
    var isExpanded by remember(code) { mutableStateOf(false) }

    val displayContent: CharSequence = remember(code, highlightedText, isExpanded, shouldCollapse) {
        val source = highlightedText ?: code
        if (shouldCollapse && !isExpanded) {
            truncateToLines(source, 20)
        } else {
            source
        }
    }

    // 代码内容
    key(code, isExpanded) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            AndroidView(
                factory = { ctx ->
                    TextView(ctx).apply {
                        typeface = Typeface.MONOSPACE
                        textSize = 13f
                        setTextColor(textColor.toArgb())
                        setBackgroundColor(surfaceColor.toArgb())
                        setPadding(
                            12.dpToPx(ctx),
                            12.dpToPx(ctx),
                            12.dpToPx(ctx),
                            12.dpToPx(ctx)
                        )
                        setTextIsSelectable(true)
                    }
                },
                update = { textView ->
                    textView.text = displayContent
                },
                modifier = Modifier.wrapContentSize()
            )
        }
    }

    // 展开/收起按钮
    val editorScheme = remember { EditorColorScheme.builtinGray() }
    if (shouldCollapse) {
        TextButton(
            onClick = { isExpanded = !isExpanded },
            modifier = Modifier
                .fillMaxWidth()
                .background(editorScheme.gutterBackground)
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text(
                text = if (isExpanded) {
                    stringResource(Strings.tina_code_block_collapse)
                } else {
                    stringResource(Strings.tina_code_block_expand, lineCount - 20)
                },
                style = MaterialTheme.typography.labelSmall,
                color = editorScheme.syntax.keyword
            )
        }
    }
}

/**
 * 将 HighlightSpan 列表转为 SpannableStringBuilder（带颜色的富文本）
 */
private fun buildHighlightedText(
    code: String,
    spans: List<HighlightSpan>,
    syntaxColors: com.wuxianggujun.tinaide.core.editorview.EditorSyntaxColors
): CharSequence {
    if (spans.isEmpty()) return code
    val builder = SpannableStringBuilder(code)
    for (span in spans) {
        val start = span.start.coerceAtLeast(0)
        val end = span.end.coerceAtMost(code.length)
        if (start >= end) continue
        val color = syntaxColors.colorOf(span.type)
        builder.setSpan(
            ForegroundColorSpan(color.toArgb()),
            start,
            end,
            SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
    return builder
}

/**
 * 截取前 N 行，保留 Spannable 格式
 */
private fun truncateToLines(source: CharSequence, maxLines: Int): CharSequence {
    var linesSeen = 0
    for (i in source.indices) {
        if (source[i] == '\n') {
            linesSeen++
            if (linesSeen >= maxLines) {
                return if (source is android.text.Spanned) {
                    SpannableStringBuilder(source, 0, i)
                } else {
                    source.subSequence(0, i)
                }
            }
        }
    }
    return source
}

private fun Int.dpToPx(context: android.content.Context): Int = (this * context.resources.displayMetrics.density).toInt()

private fun languageToExtension(language: String?): String = when (language?.lowercase()) {
    "kotlin", "kt" -> ".kt"
    "java" -> ".java"
    "python", "py" -> ".py"
    "javascript", "js" -> ".js"
    "typescript", "ts" -> ".ts"
    "c" -> ".c"
    "cpp", "c++" -> ".cpp"
    "rust", "rs" -> ".rs"
    "go" -> ".go"
    "swift" -> ".swift"
    "ruby", "rb" -> ".rb"
    "html" -> ".html"
    "css" -> ".css"
    "xml" -> ".xml"
    "json" -> ".json"
    "yaml", "yml" -> ".yaml"
    "toml" -> ".toml"
    "sql" -> ".sql"
    "bash", "sh", "shell", "zsh" -> ".sh"
    "dart" -> ".dart"
    "lua" -> ".lua"
    "php" -> ".php"
    "cmake" -> ".cmake"
    "groovy", "gradle" -> ".gradle"
    "markdown", "md" -> ".md"
    else -> ".txt"
}
