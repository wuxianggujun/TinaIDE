package com.wuxianggujun.tinaide.ui.compose.viewer

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.ui.compose.components.TinaAlertDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogCard
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogContentColumn
import com.wuxianggujun.tinaide.ui.compose.components.TinaDialogTitleText
import com.wuxianggujun.tinaide.ui.compose.components.TinaPrimaryButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaTextButton
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Selection inspector and export selection dialogs.
 */

@Composable
internal fun HexSelectionInspectorDialog(
    inspector: HexSelectionInspector,
    onDismiss: () -> Unit
) {
    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_selection_inspector_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(
                                Strings.hex_selection_inspector_range,
                                inspector.range.firstOffset,
                                inspector.range.lastOffset,
                                inspector.range.byteCount
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(
                                if (inspector.truncated) {
                                    Strings.hex_selection_inspector_sample_truncated
                                } else {
                                    Strings.hex_selection_inspector_sample
                                },
                                inspector.inspectedByteCount,
                                inspector.range.byteCount
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SelectionInspectorValueRow(
                            label = stringResource(Strings.hex_selection_inspector_hex),
                            value = inspector.hexPreview
                        )
                        SelectionInspectorValueRow(
                            label = stringResource(Strings.hex_selection_inspector_ascii),
                            value = inspector.asciiPreview
                        )
                        inspector.utf8Preview?.let { utf8Preview ->
                            SelectionInspectorValueRow(
                                label = stringResource(Strings.hex_selection_inspector_utf8),
                                value = utf8Preview
                            )
                        }
                    }
                }
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        inspector.unsigned8?.let { value ->
                            SelectionInspectorValueRow(
                                label = stringResource(Strings.hex_selection_inspector_u8),
                                value = value
                            )
                        }
                        inspector.signed8?.let { value ->
                            SelectionInspectorValueRow(
                                label = stringResource(Strings.hex_selection_inspector_i8),
                                value = value
                            )
                        }
                        inspector.unsigned16LittleEndian?.let { value ->
                            SelectionInspectorValueRow(
                                label = stringResource(Strings.hex_selection_inspector_u16_le),
                                value = value
                            )
                        }
                        inspector.unsigned16BigEndian?.let { value ->
                            SelectionInspectorValueRow(
                                label = stringResource(Strings.hex_selection_inspector_u16_be),
                                value = value
                            )
                        }
                        inspector.unsigned32LittleEndian?.let { value ->
                            SelectionInspectorValueRow(
                                label = stringResource(Strings.hex_selection_inspector_u32_le),
                                value = value
                            )
                        }
                        inspector.unsigned32BigEndian?.let { value ->
                            SelectionInspectorValueRow(
                                label = stringResource(Strings.hex_selection_inspector_u32_be),
                                value = value
                            )
                        }
                        inspector.unsigned64LittleEndianHex?.let { value ->
                            SelectionInspectorValueRow(
                                label = stringResource(Strings.hex_selection_inspector_u64_le),
                                value = value
                            )
                        }
                        inspector.unsigned64BigEndianHex?.let { value ->
                            SelectionInspectorValueRow(
                                label = stringResource(Strings.hex_selection_inspector_u64_be),
                                value = value
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
internal fun SelectionInspectorValueRow(
    label: String,
    value: String
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            overflow = TextOverflow.Ellipsis,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun ExportSelectionDialog(
    onDismiss: () -> Unit,
    onFormatSelected: (HexExportFormat) -> Unit
) {
    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_export_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        ExportFormatButton(
                            text = stringResource(Strings.hex_export_format_hex_dump),
                            onClick = { onFormatSelected(HexExportFormat.HEX_DUMP) }
                        )
                        ExportFormatButton(
                            text = stringResource(Strings.hex_export_format_c_array),
                            onClick = { onFormatSelected(HexExportFormat.C_ARRAY) }
                        )
                        ExportFormatButton(
                            text = stringResource(Strings.hex_export_format_kotlin_byte_array),
                            onClick = { onFormatSelected(HexExportFormat.KOTLIN_BYTE_ARRAY) }
                        )
                        ExportFormatButton(
                            text = stringResource(Strings.hex_export_format_base64),
                            onClick = { onFormatSelected(HexExportFormat.BASE64) }
                        )
                        ExportFormatButton(
                            text = stringResource(Strings.hex_export_format_ascii),
                            onClick = { onFormatSelected(HexExportFormat.ASCII) }
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_cancel),
                onClick = onDismiss
            )
        }
    )
}

@Composable
internal fun ExportFormatButton(
    text: String,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text)
    }
}

