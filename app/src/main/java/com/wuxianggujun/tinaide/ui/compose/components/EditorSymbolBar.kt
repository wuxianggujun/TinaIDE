package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wuxianggujun.tinaide.core.i18n.Strings

private val editorQuickSymbols = listOf(
    "{", "}", "(", ")", "[", "]", ";", "\"",
    ":", "'", "<", ">", "=", "+", "-", "*", "/", "%",
    "&", "|", "^", "~", "`", "!", "?", ".", ",", "#", "@", "\\", "\$", "_",
)

@Composable
fun EditorSymbolBar(
    onSymbolClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    TinaOverlayPanelSurface(
        modifier = modifier.height(40.dp),
        shape = RectangleShape,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LazyRow(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    start = 0.dp,
                    top = 2.dp,
                    end = 8.dp,
                    bottom = 2.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item(key = "editor-tab") {
                    EditorQuickSymbolButton(
                        text = stringResource(Strings.editor_tab_button),
                        width = 44.dp,
                        onClick = { onSymbolClick("\t") },
                    )
                }
                items(
                    items = editorQuickSymbols,
                    key = { symbol -> symbol },
                ) { symbol ->
                    EditorQuickSymbolButton(
                        text = symbol,
                        onClick = { onSymbolClick(symbol) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EditorQuickSymbolButton(
    text: String,
    width: Dp = 38.dp,
    onClick: () -> Unit,
) {
    TinaPanelSegmentButton(
        onClick = onClick,
        modifier = Modifier
            .width(width)
            .height(36.dp),
        minHeight = 36.dp,
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 15.sp,
            maxLines = 1,
        )
    }
}
