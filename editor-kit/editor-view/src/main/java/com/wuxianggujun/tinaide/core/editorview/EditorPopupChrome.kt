package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

internal val editorPopupCornerRadius = 8.dp
internal val editorPopupBorderWidth = 1.dp
internal val editorPopupElevation = 8.dp
internal val editorPopupSectionInset = 8.dp
internal val editorPopupActionPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp)
internal val editorPopupCompactActionPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)

internal data class EditorPopupColors(
    val containerColor: Color,
    val borderColor: Color,
    val dividerColor: Color,
    val selectedSurfaceColor: Color,
    val primaryTextColor: Color,
    val secondaryTextColor: Color,
    val accentColor: Color,
    val matchTextColor: Color,
    val progressTrackColor: Color
)

@Composable
internal fun rememberEditorPopupColors(
    colorScheme: EditorColorScheme
): EditorPopupColors = remember(colorScheme) { resolveEditorPopupColors(colorScheme) }

@Composable
internal fun EditorPopupSurface(
    modifier: Modifier = Modifier,
    colors: EditorPopupColors,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(editorPopupCornerRadius),
        colors = CardDefaults.cardColors(containerColor = colors.containerColor),
        border = BorderStroke(editorPopupBorderWidth, colors.borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = editorPopupElevation)
    ) {
        Box(
            modifier = Modifier.background(colors.containerColor)
        ) {
            content()
        }
    }
}

@Composable
internal fun EditorPopupScaffold(
    colors: EditorPopupColors,
    modifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier.fillMaxWidth(),
    content: @Composable ColumnScope.() -> Unit
) {
    EditorPopupSurface(
        modifier = modifier,
        colors = colors
    ) {
        Column(
            modifier = contentModifier.background(colors.containerColor),
            content = content
        )
    }
}

@Composable
internal fun EditorPopupLoadingBar(
    colors: EditorPopupColors,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(20.dp)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .clip(CircleShape),
            color = colors.accentColor,
            trackColor = colors.progressTrackColor,
            strokeCap = StrokeCap.Round
        )
    }
}

@Composable
internal fun EditorPopupDivider(
    colors: EditorPopupColors,
    modifier: Modifier = Modifier
) {
    HorizontalDivider(
        modifier = modifier.padding(horizontal = editorPopupSectionInset),
        color = colors.dividerColor
    )
}

@Composable
internal fun EditorPopupActionButton(
    onClick: () -> Unit,
    colors: EditorPopupColors,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = colors.primaryTextColor,
    disabledContentColor: Color = colors.secondaryTextColor.copy(alpha = 0.45f),
    contentPadding: PaddingValues = editorPopupActionPadding,
    content: @Composable RowScope.() -> Unit
) {
    TextButton(
        modifier = modifier,
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(
            contentColor = contentColor,
            disabledContentColor = disabledContentColor
        ),
        contentPadding = contentPadding,
        content = content
    )
}

internal fun resolveEditorPopupColors(scheme: EditorColorScheme): EditorPopupColors {
    val darkTheme = scheme.background.perceivedBrightness() < 0.5f
    val container = mixColor(
        start = scheme.background,
        end = scheme.foreground,
        ratio = if (darkTheme) 0.055f else 0.018f
    )
    val border = mixColor(
        start = scheme.gutterDivider,
        end = scheme.foreground,
        ratio = if (darkTheme) 0.22f else 0.08f
    ).copy(alpha = if (darkTheme) 0.92f else 0.6f)
    val divider = mixColor(
        start = scheme.gutterDivider,
        end = scheme.foreground,
        ratio = if (darkTheme) 0.3f else 0.14f
    ).copy(alpha = if (darkTheme) 0.72f else 0.45f)
    val selected = mixColor(
        start = scheme.selectionBackground,
        end = scheme.currentLineBackground,
        ratio = if (darkTheme) 0.35f else 0.45f
    ).copy(alpha = if (darkTheme) 0.88f else 0.94f)
    val secondaryText = mixColor(
        start = scheme.lineNumberForeground,
        end = scheme.foreground,
        ratio = if (darkTheme) 0.18f else 0.1f
    ).copy(alpha = if (darkTheme) 0.95f else 0.82f)
    val matchedText = mixColor(
        start = scheme.syntax.keyword,
        end = scheme.selectionHandle,
        ratio = 0.25f
    )
    val progressTrack = mixColor(
        start = container,
        end = scheme.foreground,
        ratio = if (darkTheme) 0.12f else 0.05f
    )
    return EditorPopupColors(
        containerColor = container,
        borderColor = border,
        dividerColor = divider,
        selectedSurfaceColor = selected,
        primaryTextColor = scheme.foreground,
        secondaryTextColor = secondaryText,
        accentColor = scheme.selectionHandle,
        matchTextColor = matchedText,
        progressTrackColor = progressTrack
    )
}

private fun mixColor(start: Color, end: Color, ratio: Float): Color = lerp(start, end, ratio.coerceIn(0f, 1f))

private fun Color.perceivedBrightness(): Float = (red * 0.299f) + (green * 0.587f) + (blue * 0.114f)
