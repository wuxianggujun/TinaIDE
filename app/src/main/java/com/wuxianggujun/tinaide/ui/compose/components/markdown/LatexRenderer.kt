package com.wuxianggujun.tinaide.ui.compose.components.markdown

import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.takeOrElse
import ru.noties.jlatexmath.JLatexMathDrawable
import timber.log.Timber

/**
 * 预估 LaTeX 公式渲染尺寸（用于 InlineTextContent placeholder 计算）
 */
fun assumeLatexSize(latex: String, fontSize: Float): Rect {
    val renderableLatex = LatexRenderPolicy.prepareForRendering(latex) ?: return Rect(0, 0, 0, 0)
    return runCatching {
        JLatexMathDrawable.builder(renderableLatex)
            .textSize(fontSize)
            .padding(0)
            .build()
            .bounds
            .takeIf(::hasSafeRenderBounds)
            ?: Rect(0, 0, 0, 0)
    }.getOrElse { Rect(0, 0, 0, 0) }
}

/**
 * LaTeX 公式渲染组件
 *
 * 使用 JLatexMathDrawable 在 Canvas 上绘制，渲染失败时 fallback 到等宽文本。
 */
@Composable
fun LatexText(
    latex: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
) {
    val mergedStyle = style.merge(fontSize = fontSize, color = color)
    val density = LocalDensity.current
    val renderableLatex = remember(latex) { LatexRenderPolicy.prepareForRendering(latex) }

    val drawable = remember(renderableLatex, fontSize, mergedStyle, density) {
        if (renderableLatex == null) return@remember null
        runCatching {
            with(density) {
                JLatexMathDrawable.builder(renderableLatex)
                    .textSize(fontSize.takeOrElse { mergedStyle.fontSize }.toPx())
                    .color(mergedStyle.color.toArgb())
                    .background(mergedStyle.background.toArgb())
                    .padding(0)
                    .align(JLatexMathDrawable.ALIGN_LEFT)
                    .build()
                    .takeIf { hasSafeRenderBounds(it.bounds) }
            }
        }.onFailure { error ->
            Timber.tag("LatexRenderer").w(
                "Failed to render LaTeX: error=%s",
                error.javaClass.simpleName,
            )
        }.getOrNull()
    }

    if (drawable != null) {
        with(density) {
            Canvas(
                modifier = modifier.size(
                    width = drawable.bounds.width().toDp(),
                    height = drawable.bounds.height().toDp(),
                ),
            ) {
                drawable.draw(drawContext.canvas.nativeCanvas)
            }
        }
    } else {
        Text(
            text = LatexRenderPolicy.fallbackText(latex),
            style = mergedStyle.copy(fontFamily = FontFamily.Monospace),
            modifier = modifier,
        )
    }
}

/**
 * 行内数学公式（嵌入段落文字中）
 */
@Composable
fun MathInline(
    latex: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified,
) {
    LatexText(
        latex = latex,
        color = LocalContentColor.current,
        fontSize = fontSize.takeOrElse { LocalTextStyle.current.fontSize },
        modifier = modifier,
    )
}

/**
 * 块级数学公式（独占一行，居中显示）
 */
@Composable
fun MathBlock(
    latex: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified,
) {
    Box(modifier = modifier.padding(8.dp)) {
        LatexText(
            latex = latex,
            color = LocalContentColor.current,
            fontSize = fontSize.takeOrElse { MaterialTheme.typography.bodyLarge.fontSize },
            modifier = Modifier
                .align(Alignment.Center)
                .horizontalScroll(rememberScrollState()),
        )
    }
}

private fun hasSafeRenderBounds(bounds: Rect): Boolean {
    val width = bounds.right.toLong() - bounds.left.toLong()
    val height = bounds.bottom.toLong() - bounds.top.toLong()
    return width in 1..LatexRenderPolicy.MAX_RENDER_DIMENSION_PX.toLong() &&
        height in 1..LatexRenderPolicy.MAX_RENDER_DIMENSION_PX.toLong()
}
