package com.wuxianggujun.tinaide.ui.compose.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.lsp.Diagnostic

/**
 * 诊断列表内容
 */
@Composable
fun DiagnosticsContent(
    diagnostics: List<Diagnostic>,
    onDiagnosticClick: (Diagnostic) -> Unit,
    onDiagnosticCodeActionsClick: (Diagnostic) -> Unit,
    modifier: Modifier = Modifier
) {
    if (diagnostics.isEmpty()) {
        EmptyStateContent(
            message = stringResource(Strings.diagnostics_empty),
            icon = Icons.Default.BugReport,
            modifier = modifier
        )
    } else {
        val groupedDiagnostics = remember(diagnostics) {
            diagnostics.groupBy { it.fileName }
        }

        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            groupedDiagnostics.forEach { (fileName, fileDiagnostics) ->
                item {
                    DiagnosticFileHeader(fileName = fileName, count = fileDiagnostics.size)
                }

                items(fileDiagnostics) { diagnostic ->
                    DiagnosticItem(
                        diagnostic = diagnostic,
                        onClick = { onDiagnosticClick(diagnostic) },
                        onCodeActionsClick = { onDiagnosticCodeActionsClick(diagnostic) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticFileHeader(
    fileName: String,
    count: Int,
    modifier: Modifier = Modifier
) {
    TinaOverlayPanelSurface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = fileName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.width(8.dp))

            TinaOverlayPanelSurface(
                shape = MaterialTheme.shapes.small,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Text(
                    text = count.toString(),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiagnosticItem(
    diagnostic: Diagnostic,
    onClick: () -> Unit,
    onCodeActionsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }

    val (severityColor, severityIcon, severityLabel) = when (diagnostic.severity) {
        Diagnostic.Severity.ERROR -> Triple(
            MaterialTheme.colorScheme.error,
            Icons.Default.Error,
            stringResource(Strings.diagnostic_error)
        )
        Diagnostic.Severity.WARNING -> Triple(
            TinaSemanticColors.Diagnostic.warning,
            Icons.Default.Warning,
            stringResource(Strings.diagnostic_warning)
        )
        Diagnostic.Severity.INFO -> Triple(
            MaterialTheme.colorScheme.primary,
            Icons.Default.Info,
            stringResource(Strings.diagnostic_information)
        )
        Diagnostic.Severity.HINT -> Triple(
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Default.Info,
            stringResource(Strings.diagnostic_hint)
        )
    }
    val diagnosticClipboardLabel = stringResource(Strings.bottom_panel_diagnostics)
    val diagnosticCopiedText = stringResource(Strings.diagnostic_copied)

    // 长按复制诊断信息
    val onLongClickCopy = {
        val copyText = buildString {
            append(diagnostic.displayLocation)
            append(" [$severityLabel]")
            append("\n")
            append(diagnostic.message)
        }
        val clip = ClipData.newPlainText(diagnosticClipboardLabel, copyText)
        clipboardManager.setPrimaryClip(clip)
        Toast.makeText(context, diagnosticCopiedText, Toast.LENGTH_SHORT).show()
    }

    TinaDialogCard(
        modifier = modifier
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClickCopy
            ),
        contentPadding = PaddingValues(12.dp),
        color = severityColor.copy(alpha = 0.08f),
        border = BorderStroke(
            1.dp,
            severityColor.copy(alpha = 0.18f)
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = severityIcon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = severityColor
            )

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = diagnostic.message,
                    color = severityColor,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = severityLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = severityColor
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TinaOverlayPanelSurface(
                shape = MaterialTheme.shapes.small,
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Text(
                    text = stringResource(Strings.diagnostics_location, diagnostic.line + 1, diagnostic.column + 1),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }

            TinaTextButton(
                text = stringResource(Strings.diagnostics_view_fixes),
                onClick = onCodeActionsClick,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}
