package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wuxianggujun.tinaide.core.i18n.Strings

private val editorQuickSymbols = listOf("{", "}", "(", ")", "[", "]", ";", "#")

@Composable
fun EditorSymbolBar(
    onSymbolClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val barHeight = if (expanded) 40.dp else 32.dp

    TinaOverlayPanelSurface(
        modifier = modifier.height(barHeight),
        shape = RectangleShape,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TinaPanelSegmentButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.size(32.dp),
                minHeight = 32.dp,
                contentPadding = PaddingValues(0.dp),
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = stringResource(
                        if (expanded) Strings.content_desc_collapse else Strings.content_desc_expand
                    ),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EditorQuickSymbolButton(
                        text = stringResource(Strings.editor_tab_button),
                        width = 44.dp,
                        onClick = { onSymbolClick("\t") },
                    )
                    editorQuickSymbols.forEach { symbol ->
                        EditorQuickSymbolButton(
                            text = symbol,
                            onClick = { onSymbolClick(symbol) },
                        )
                    }
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
