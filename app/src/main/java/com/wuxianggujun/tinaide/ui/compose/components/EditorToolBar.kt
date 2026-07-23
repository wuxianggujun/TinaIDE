package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wuxianggujun.tinaide.core.i18n.Drawables
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.ui.compose.icons.rememberTinaPainter

/**
 * Compose 版本的编辑器工具栏
 *
 * 功能：
 * - 核心编辑操作：撤销、重做
 * - 常用编程符号快捷输入
 * - 支持水平滚动
 */
@Composable
fun EditorToolBar(
    modifier: Modifier = Modifier,
    canUndo: Boolean = true,
    canRedo: Boolean = true,
    onUndoClick: () -> Unit = {},
    onRedoClick: () -> Unit = {},
    onSymbolClick: (String) -> Unit = {}
) {
    val scrollState = rememberScrollState()

    // 默认只放 C/C++ 高频符号，完整列表点「更多」展开，减少对编辑器高度的挤压
    val primarySymbols = listOf("{", "}", "(", ")", "[", "]", ";", "\"")
    val secondarySymbols = listOf(
        ":", "'", "<", ">",
        "=", "+", "-", "*", "/", "%",
        "&", "|", "^", "~", "!", "?",
        ".", ",", "#", "@", "\\", "$"
    )
    var showMoreSymbols by remember { mutableStateOf(false) }

    // Tab 特殊符号（显示为 "TAB"，点击时插入 "\t"）
    val tabSymbol = "\t"
    val symbols = if (showMoreSymbols) primarySymbols + secondarySymbols else primarySymbols

    TinaOverlayPanelSurface(
        modifier = modifier.height(40.dp),
        shape = RectangleShape,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(scrollState)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            // 工具按钮
            ToolIconButton(
                iconRes = Drawables.ic_undo,
                contentDescription = stringResource(Strings.content_desc_undo),
                enabled = canUndo,
                onClick = {
                    onUndoClick()
                }
            )

            ToolIconButton(
                iconRes = Drawables.ic_redo,
                contentDescription = stringResource(Strings.content_desc_redo),
                enabled = canRedo,
                onClick = {
                    onRedoClick()
                }
            )

            // 分隔符
            Spacer(modifier = Modifier.width(8.dp))
            VerticalDivider(
                modifier = Modifier.height(24.dp),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.width(8.dp))

            // Tab 特殊按钮
            TabButton(
                onClick = { onSymbolClick(tabSymbol) }
            )

            // 符号按钮
            symbols.forEach { symbol ->
                SymbolButton(
                    symbol = symbol,
                    onClick = { onSymbolClick(symbol) }
                )
            }

            Spacer(modifier = Modifier.width(4.dp))
            SymbolButton(
                symbol = if (showMoreSymbols) "«" else "…",
                onClick = { showMoreSymbols = !showMoreSymbols }
            )
        }
    }
}

@Composable
private fun ToolIconButton(
    iconRes: Int,
    contentDescription: String,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    onClick: () -> Unit
) {
    TinaPanelSegmentButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(40.dp),
        contentPadding = PaddingValues(0.dp)
    ) {
        val tint = when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            highlighted -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Icon(
            painter = rememberTinaPainter(iconRes),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun SymbolButton(
    symbol: String,
    onClick: () -> Unit
) {
    TinaPanelSegmentButton(
        onClick = onClick,
        modifier = Modifier.size(36.dp),
        minHeight = 36.dp,
        contentPadding = PaddingValues(0.dp)
    ) {
        Text(
            text = symbol,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TabButton(
    onClick: () -> Unit
) {
    TinaPanelSegmentButton(
        onClick = onClick,
        modifier = Modifier
            .width(42.dp)
            .height(36.dp),
        minHeight = 36.dp,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
    ) {
        Text(
            text = stringResource(Strings.editor_tab_button),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
