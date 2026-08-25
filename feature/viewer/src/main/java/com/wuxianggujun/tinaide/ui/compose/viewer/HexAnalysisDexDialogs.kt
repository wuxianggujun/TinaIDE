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
 * DEX analysis dialogs.
 */

@Composable
internal fun DexStringsDialog(
    entries: List<HexDexStringEntry>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(entries) { mutableStateOf("") }
    val filteredEntries = remember(entries, query) {
        filterDexStringEntries(entries = entries, query = query)
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_dex_strings_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(Strings.hex_dex_strings_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = stringResource(
                                Strings.hex_dex_strings_filter_count,
                                filteredEntries.size,
                                entries.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    if (filteredEntries.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_dex_strings_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = filteredEntries.size,
                                key = { index ->
                                    val entry = filteredEntries[index]
                                    "${entry.index}-${entry.dataOffset}-${entry.value}"
                                }
                            ) { index ->
                                DexStringEntryRow(
                                    entry = filteredEntries[index],
                                    onGotoOffset = onGotoOffset
                                )
                            }
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
internal fun DexStringEntryRow(
    entry: HexDexStringEntry,
    onGotoOffset: (Long) -> Unit
) {
    Surface(
        onClick = { onGotoOffset(entry.dataOffset) },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = entry.value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    Strings.hex_dex_string_meta,
                    entry.index,
                    entry.stringIdOffset,
                    entry.dataOffset
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun DexTypesDialog(
    entries: List<HexDexTypeEntry>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(entries) { mutableStateOf("") }
    val filteredEntries = remember(entries, query) {
        filterDexTypeEntries(entries = entries, query = query)
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_dex_types_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(Strings.hex_dex_types_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = stringResource(
                                Strings.hex_dex_types_filter_count,
                                filteredEntries.size,
                                entries.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    if (filteredEntries.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_dex_types_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = filteredEntries.size,
                                key = { index ->
                                    val entry = filteredEntries[index]
                                    "${entry.index}-${entry.typeIdOffset}-${entry.descriptor}"
                                }
                            ) { index ->
                                DexTypeEntryRow(
                                    entry = filteredEntries[index],
                                    onGotoOffset = onGotoOffset
                                )
                            }
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
internal fun DexTypeEntryRow(
    entry: HexDexTypeEntry,
    onGotoOffset: (Long) -> Unit
) {
    Surface(
        onClick = { onGotoOffset(entry.typeIdOffset) },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = entry.descriptor,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    Strings.hex_dex_type_meta,
                    entry.index,
                    entry.typeIdOffset,
                    entry.descriptorStringIndex
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun DexProtosDialog(
    entries: List<HexDexProtoEntry>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(entries) { mutableStateOf("") }
    val filteredEntries = remember(entries, query) {
        filterDexProtoEntries(entries = entries, query = query)
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_dex_protos_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(Strings.hex_dex_protos_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = stringResource(
                                Strings.hex_dex_protos_filter_count,
                                filteredEntries.size,
                                entries.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    if (filteredEntries.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_dex_protos_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = filteredEntries.size,
                                key = { index ->
                                    val entry = filteredEntries[index]
                                    "${entry.index}-${entry.protoIdOffset}-${entry.signature}"
                                }
                            ) { index ->
                                DexProtoEntryRow(
                                    entry = filteredEntries[index],
                                    onGotoOffset = onGotoOffset
                                )
                            }
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
internal fun DexProtoEntryRow(
    entry: HexDexProtoEntry,
    onGotoOffset: (Long) -> Unit
) {
    Surface(
        onClick = { onGotoOffset(entry.protoIdOffset) },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = entry.signature,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    Strings.hex_dex_proto_meta,
                    entry.index,
                    entry.protoIdOffset,
                    entry.shorty,
                    entry.shortyStringIndex,
                    entry.returnTypeIndex,
                    entry.parametersOffset
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun DexFieldsDialog(
    entries: List<HexDexFieldEntry>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(entries) { mutableStateOf("") }
    val filteredEntries = remember(entries, query) {
        filterDexFieldEntries(entries = entries, query = query)
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_dex_fields_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(Strings.hex_dex_fields_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = stringResource(
                                Strings.hex_dex_fields_filter_count,
                                filteredEntries.size,
                                entries.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    if (filteredEntries.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_dex_fields_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = filteredEntries.size,
                                key = { index ->
                                    val entry = filteredEntries[index]
                                    "${entry.index}-${entry.fieldIdOffset}-${entry.name}"
                                }
                            ) { index ->
                                DexFieldEntryRow(
                                    entry = filteredEntries[index],
                                    onGotoOffset = onGotoOffset
                                )
                            }
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
internal fun DexFieldEntryRow(
    entry: HexDexFieldEntry,
    onGotoOffset: (Long) -> Unit
) {
    Surface(
        onClick = { onGotoOffset(entry.fieldIdOffset) },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = stringResource(
                    Strings.hex_dex_field_display,
                    entry.classDescriptor,
                    entry.name,
                    entry.typeDescriptor
                ),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    Strings.hex_dex_field_meta,
                    entry.index,
                    entry.fieldIdOffset,
                    entry.classIndex,
                    entry.typeIndex,
                    entry.nameStringIndex
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun DexMethodsDialog(
    entries: List<HexDexMethodEntry>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(entries) { mutableStateOf("") }
    val filteredEntries = remember(entries, query) {
        filterDexMethodEntries(entries = entries, query = query)
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_dex_methods_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(Strings.hex_dex_methods_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = stringResource(
                                Strings.hex_dex_methods_filter_count,
                                filteredEntries.size,
                                entries.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TinaDialogCard {
                    if (filteredEntries.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_dex_methods_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                count = filteredEntries.size,
                                key = { index ->
                                    val entry = filteredEntries[index]
                                    "${entry.index}-${entry.methodIdOffset}-${entry.name}"
                                }
                            ) { index ->
                                DexMethodEntryRow(
                                    entry = filteredEntries[index],
                                    onGotoOffset = onGotoOffset
                                )
                            }
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
internal fun DexMethodEntryRow(
    entry: HexDexMethodEntry,
    onGotoOffset: (Long) -> Unit
) {
    Surface(
        onClick = { onGotoOffset(entry.methodIdOffset) },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = stringResource(
                    Strings.hex_dex_method_display,
                    entry.classDescriptor,
                    entry.name,
                    entry.protoSignature
                ),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    Strings.hex_dex_method_meta,
                    entry.index,
                    entry.methodIdOffset,
                    entry.classIndex,
                    entry.protoIndex,
                    entry.protoShorty,
                    entry.nameStringIndex
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

