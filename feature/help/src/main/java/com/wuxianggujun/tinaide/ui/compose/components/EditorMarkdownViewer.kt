package com.wuxianggujun.tinaide.ui.compose.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wuxianggujun.tinaide.core.editorview.EditorColorScheme
import com.wuxianggujun.tinaide.core.editorview.EditorConfig
import com.wuxianggujun.tinaide.core.editorview.EditorState
import com.wuxianggujun.tinaide.core.editorview.TinaCodeViewer
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import com.wuxianggujun.tinaide.core.treesitter.SyntaxHighlighter
import com.wuxianggujun.tinaide.core.treesitter.TreeSitterHighlighter
import com.wuxianggujun.tinaide.core.treesitter.TreeSitterLanguageRegistry

/** Rendered documentation with immutable, highlighted TinaEditor code surfaces. */
@Composable
fun EditorMarkdownViewer(
    markdown: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
    onLinkClick: ((String) -> Unit)? = null,
    onCodeCopy: ((String) -> Unit)? = null,
) {
    MarkdownViewer(markdown, modifier, contentPadding, onLinkClick, onCodeCopy) { code, language, blockModifier, copy ->
        EditorMarkdownCodeBlock(code, language, blockModifier, copy)
    }
}

private const val CODE_FONT_SIZE_SP = 14f
private const val COLLAPSED_CODE_LINES = 18

@Composable
private fun EditorMarkdownCodeBlock(
    code: String,
    language: String?,
    modifier: Modifier,
    onCopy: ((String) -> Unit)?,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val scheme = remember(dark) {
        (if (dark) EditorColorScheme.builtinDark() else EditorColorScheme.builtinLight())
            .copy(currentLineBackground = Color.Transparent)
    }
    val document = remember(context, code, language) {
        MarkdownCodeDocument(code, language) { TreeSitterHighlighter.create(context, it) }
    }
    DisposableEffect(document) { onDispose { document.close() } }
    SideEffect { document.state.colorScheme = scheme }
    var expanded by rememberSaveable(code, language) { mutableStateOf(false) }
    val lineCount = document.state.textBuffer.lineCount
    val lineHeightPx = remember(density) {
        Paint().apply { typeface = Typeface.MONOSPACE; textSize = with(density) { CODE_FONT_SIZE_SP.sp.toPx() } }
            .fontMetrics.let { it.descent - it.ascent + it.leading }
    }
    val visibleLines = if (expanded) lineCount else lineCount.coerceAtMost(COLLAPSED_CODE_LINES)
    val height = with(density) { (lineHeightPx * visibleLines.coerceAtLeast(1)).toDp() }
    Surface(modifier, shape = RoundedCornerShape(8.dp), color = scheme.background) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(language.orEmpty(), style = MaterialTheme.typography.labelSmall, color = scheme.lineNumberForeground)
                if (onCopy != null) {
                    TextButton(onClick = { onCopy(code) }, modifier = Modifier.testTag("markdown-code-copy")) {
                        Text(stringResource(Strings.action_copy))
                    }
                }
            }
            TinaCodeViewer(document.state, Modifier.fillMaxWidth().padding(8.dp).height(height))
            if (lineCount > COLLAPSED_CODE_LINES) {
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (expanded) stringResource(Strings.tina_code_block_collapse)
                        else stringResource(Strings.tina_code_block_expand, lineCount - COLLAPSED_CODE_LINES))
                }
            }
        }
    }
}

/** One immutable snapshot owns its buffer, parser and update subscription; no LSP is created. */
internal class MarkdownCodeDocument(
    code: String,
    language: String?,
    createHighlighter: (String) -> SyntaxHighlighter?,
) : AutoCloseable {
    private val buffer = RopeTextBuffer(code)
    val state = EditorState(buffer, config = EditorConfig(
        showLineNumbers = false,
        fontSizeSp = CODE_FONT_SIZE_SP,
        wordWrap = false,
        codeFolding = false,
        rainbowBrackets = false,
        bracketPairGuides = false,
    ))
    private val highlighter = TreeSitterLanguageRegistry.resolveLanguageName(language)?.let(createHighlighter)
    private var closed = false

    init {
        state.highlighter = highlighter
        highlighter?.openDocument(code)
    }

    override fun close() {
        if (closed) return
        closed = true
        state.close()
        highlighter?.dispose()
        buffer.close()
    }
}
