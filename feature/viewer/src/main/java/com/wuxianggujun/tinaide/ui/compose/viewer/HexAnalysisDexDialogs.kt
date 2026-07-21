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

@Composable
internal fun DexClassesDialog(
    entries: List<HexDexClassDefEntry>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(entries) { mutableStateOf("") }
    val filteredEntries = remember(entries, query) {
        filterDexClassDefEntries(entries = entries, query = query)
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_dex_classes_dialog_title)) },
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
                            label = { Text(stringResource(Strings.hex_dex_classes_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = stringResource(
                                Strings.hex_dex_classes_filter_count,
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
                            text = stringResource(Strings.hex_dex_classes_empty),
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
                                    "${entry.index}-${entry.classDefOffset}-${entry.classDescriptor}"
                                }
                            ) { index ->
                                DexClassDefEntryRow(
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
internal fun DexClassDefEntryRow(
    entry: HexDexClassDefEntry,
    onGotoOffset: (Long) -> Unit
) {
    val superclassLabel = entry.superclassDescriptor ?: stringResource(Strings.hex_dex_index_none)
    Surface(
        onClick = { onGotoOffset(entry.classDefOffset) },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = entry.classDescriptor,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    Strings.hex_dex_class_meta,
                    entry.index,
                    entry.classDefOffset,
                    entry.classIndex,
                    entry.accessFlags,
                    superclassLabel,
                    entry.classDataOffset
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            entry.sourceFile?.let { sourceFile ->
                Text(
                    text = stringResource(Strings.hex_dex_class_source, sourceFile),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
internal fun DexClassDataMethodsDialog(
    entries: List<HexDexClassDataMethodEntry>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(entries) { mutableStateOf("") }
    val filteredEntries = remember(entries, query) {
        filterDexClassDataMethodEntries(entries = entries, query = query)
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_dex_class_data_methods_dialog_title)) },
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
                            label = { Text(stringResource(Strings.hex_dex_class_data_methods_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = stringResource(
                                Strings.hex_dex_class_data_methods_filter_count,
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
                            text = stringResource(Strings.hex_dex_class_data_methods_empty),
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
                                    "${entry.index}-${entry.entryOffset}-${entry.codeOffset}-${entry.methodName}"
                                }
                            ) { index ->
                                DexClassDataMethodEntryRow(
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
internal fun DexClassDataMethodEntryRow(
    entry: HexDexClassDataMethodEntry,
    onGotoOffset: (Long) -> Unit
) {
    val targetOffset = if (entry.codeOffset > 0L) entry.codeOffset else entry.entryOffset
    Surface(
        onClick = { onGotoOffset(targetOffset) },
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
                    Strings.hex_dex_class_data_method_display,
                    entry.methodClassDescriptor,
                    entry.methodName,
                    entry.protoSignature
                ),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    Strings.hex_dex_class_data_method_meta,
                    entry.index,
                    dexClassDataMethodKindLabel(entry.kind),
                    entry.methodIndex,
                    entry.accessFlags,
                    entry.entryOffset,
                    entry.codeOffset,
                    dexClassDataMethodExecutionKindLabel(entry.executionKind)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun DexCodeItemsDialog(
    entries: List<HexDexCodeItemEntry>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(entries) { mutableStateOf("") }
    val filteredEntries = remember(entries, query) {
        filterDexCodeItemEntries(entries = entries, query = query)
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_dex_code_items_dialog_title)) },
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
                            label = { Text(stringResource(Strings.hex_dex_code_items_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = stringResource(
                                Strings.hex_dex_code_items_filter_count,
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
                            text = stringResource(Strings.hex_dex_code_items_empty),
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
                                    "${entry.index}-${entry.codeOffset}-${entry.methodName}-${entry.firstOpcode}"
                                }
                            ) { index ->
                                DexCodeItemEntryRow(
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
internal fun DexCodeItemEntryRow(
    entry: HexDexCodeItemEntry,
    onGotoOffset: (Long) -> Unit
) {
    val previewText = if (entry.previewCodeUnitsHex.isEmpty()) {
        stringResource(Strings.hex_dex_code_item_preview_empty)
    } else {
        entry.previewCodeUnitsHex
    }
    Surface(
        onClick = { onGotoOffset(entry.codeOffset) },
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
                    Strings.hex_dex_code_item_display,
                    entry.methodClassDescriptor,
                    entry.methodName,
                    entry.protoSignature
                ),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    Strings.hex_dex_code_item_meta,
                    entry.index,
                    entry.methodIndex,
                    entry.registersSize,
                    entry.insSize,
                    entry.outsSize,
                    entry.triesSize,
                    entry.debugInfoOffset,
                    entry.insnsSize,
                    entry.firstOpcodeName,
                    entry.firstOpcode
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(
                    Strings.hex_dex_code_item_preview,
                    previewText
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun DexCallReferencesDialog(
    entries: List<HexDexCallReferenceEntry>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit,
    onMarkOffsets: (List<Long>) -> Unit
) {
    var query by remember(entries) { mutableStateOf("") }
    val filteredEntries = remember(entries, query) {
        filterDexCallReferenceEntries(entries = entries, query = query)
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_dex_call_references_dialog_title)) },
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
                            label = { Text(stringResource(Strings.hex_dex_call_references_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(
                                    Strings.hex_dex_call_references_filter_count,
                                    filteredEntries.size,
                                    entries.size
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = {
                                    onMarkOffsets(filteredEntries.map { entry -> entry.instructionOffset })
                                },
                                enabled = filteredEntries.isNotEmpty()
                            ) {
                                Text(stringResource(Strings.hex_bookmark_mark_visible))
                            }
                        }
                    }
                }
                TinaDialogCard {
                    if (filteredEntries.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_dex_call_references_empty),
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
                                    "${entry.index}-${entry.instructionOffset}-${entry.targetMethodIndex}"
                                }
                            ) { index ->
                                DexCallReferenceEntryRow(
                                    entry = filteredEntries[index],
                                    onGotoOffset = onGotoOffset,
                                    onMarkOffset = { offset -> onMarkOffsets(listOf(offset)) }
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
internal fun DexCallReferenceEntryRow(
    entry: HexDexCallReferenceEntry,
    onGotoOffset: (Long) -> Unit,
    onMarkOffset: (Long) -> Unit
) {
    Surface(
        onClick = { onGotoOffset(entry.instructionOffset) },
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
                    Strings.hex_dex_call_reference_display,
                    entry.callerClassDescriptor,
                    entry.callerMethodName,
                    entry.targetClassDescriptor,
                    entry.targetMethodName
                ),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    Strings.hex_dex_call_reference_meta,
                    entry.index,
                    entry.opcodeName,
                    entry.opcode,
                    entry.callerMethodIndex,
                    entry.targetMethodIndex,
                    entry.instructionOffset,
                    entry.codeOffset,
                    entry.targetMethodIdOffset ?: -1L
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(
                    Strings.hex_dex_call_reference_signature,
                    entry.callerProtoSignature,
                    entry.targetProtoSignature
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            TextButton(onClick = { onMarkOffset(entry.instructionOffset) }) {
                Text(stringResource(Strings.hex_bookmark_mark))
            }
        }
    }
}

@Composable
internal fun DexStringReferencesDialog(
    entries: List<HexDexStringReferenceEntry>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit,
    onMarkOffsets: (List<Long>) -> Unit
) {
    var query by remember(entries) { mutableStateOf("") }
    val filteredEntries = remember(entries, query) {
        filterDexStringReferenceEntries(entries = entries, query = query)
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_dex_string_references_dialog_title)) },
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
                            label = { Text(stringResource(Strings.hex_dex_string_references_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(
                                    Strings.hex_dex_string_references_filter_count,
                                    filteredEntries.size,
                                    entries.size
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = {
                                    onMarkOffsets(filteredEntries.map { entry -> entry.instructionOffset })
                                },
                                enabled = filteredEntries.isNotEmpty()
                            ) {
                                Text(stringResource(Strings.hex_bookmark_mark_visible))
                            }
                        }
                    }
                }
                TinaDialogCard {
                    if (filteredEntries.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_dex_string_references_empty),
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
                                    "${entry.index}-${entry.instructionOffset}-${entry.stringIndex}"
                                }
                            ) { index ->
                                DexStringReferenceEntryRow(
                                    entry = filteredEntries[index],
                                    onGotoOffset = onGotoOffset,
                                    onMarkOffset = { offset -> onMarkOffsets(listOf(offset)) }
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
internal fun DexStringReferenceEntryRow(
    entry: HexDexStringReferenceEntry,
    onGotoOffset: (Long) -> Unit,
    onMarkOffset: (Long) -> Unit
) {
    Surface(
        onClick = { onGotoOffset(entry.instructionOffset) },
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
                    Strings.hex_dex_string_reference_display,
                    entry.callerClassDescriptor,
                    entry.callerMethodName,
                    entry.value
                ),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    Strings.hex_dex_string_reference_meta,
                    entry.index,
                    entry.opcodeName,
                    entry.opcode,
                    entry.callerMethodIndex,
                    entry.stringIndex,
                    entry.instructionOffset,
                    entry.codeOffset,
                    entry.stringIdOffset ?: -1L,
                    entry.stringDataOffset ?: -1L
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = { onMarkOffset(entry.instructionOffset) }) {
                Text(stringResource(Strings.hex_bookmark_mark))
            }
        }
    }
}

@Composable
internal fun DexFieldReferencesDialog(
    entries: List<HexDexFieldReferenceEntry>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit,
    onMarkOffsets: (List<Long>) -> Unit
) {
    var query by remember(entries) { mutableStateOf("") }
    val filteredEntries = remember(entries, query) {
        filterDexFieldReferenceEntries(entries = entries, query = query)
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_dex_field_references_dialog_title)) },
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
                            label = { Text(stringResource(Strings.hex_dex_field_references_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(
                                    Strings.hex_dex_field_references_filter_count,
                                    filteredEntries.size,
                                    entries.size
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = {
                                    onMarkOffsets(filteredEntries.map { entry -> entry.instructionOffset })
                                },
                                enabled = filteredEntries.isNotEmpty()
                            ) {
                                Text(stringResource(Strings.hex_bookmark_mark_visible))
                            }
                        }
                    }
                }
                TinaDialogCard {
                    if (filteredEntries.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_dex_field_references_empty),
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
                                    "${entry.index}-${entry.instructionOffset}-${entry.fieldIndex}"
                                }
                            ) { index ->
                                DexFieldReferenceEntryRow(
                                    entry = filteredEntries[index],
                                    onGotoOffset = onGotoOffset,
                                    onMarkOffset = { offset -> onMarkOffsets(listOf(offset)) }
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
internal fun DexFieldReferenceEntryRow(
    entry: HexDexFieldReferenceEntry,
    onGotoOffset: (Long) -> Unit,
    onMarkOffset: (Long) -> Unit
) {
    Surface(
        onClick = { onGotoOffset(entry.instructionOffset) },
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
                    Strings.hex_dex_field_reference_display,
                    entry.callerClassDescriptor,
                    entry.callerMethodName,
                    entry.fieldClassDescriptor,
                    entry.fieldName,
                    entry.fieldTypeDescriptor
                ),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    Strings.hex_dex_field_reference_meta,
                    entry.index,
                    entry.opcodeName,
                    entry.opcode,
                    entry.callerMethodIndex,
                    entry.fieldIndex,
                    entry.instructionOffset,
                    entry.codeOffset,
                    entry.fieldIdOffset ?: -1L
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = { onMarkOffset(entry.instructionOffset) }) {
                Text(stringResource(Strings.hex_bookmark_mark))
            }
        }
    }
}

@Composable
internal fun DexMapEntriesDialog(
    entries: List<HexDexMapEntry>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit
) {
    var query by remember(entries) { mutableStateOf("") }
    var mapFilter by remember(entries) { mutableStateOf(DexMapEntryFilter.ALL) }
    val filteredEntries = remember(entries, query, mapFilter) {
        filterDexMapEntries(
            entries = entries,
            query = query,
            mapFilter = mapFilter
        )
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_dex_map_dialog_title)) },
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
                            label = { Text(stringResource(Strings.hex_dex_map_filter_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            DexMapFilterButton(
                                filter = DexMapEntryFilter.ALL,
                                selected = mapFilter == DexMapEntryFilter.ALL,
                                onClick = { mapFilter = DexMapEntryFilter.ALL }
                            )
                            DexMapFilterButton(
                                filter = DexMapEntryFilter.IDS,
                                selected = mapFilter == DexMapEntryFilter.IDS,
                                onClick = { mapFilter = DexMapEntryFilter.IDS }
                            )
                            DexMapFilterButton(
                                filter = DexMapEntryFilter.CLASS_DATA,
                                selected = mapFilter == DexMapEntryFilter.CLASS_DATA,
                                onClick = { mapFilter = DexMapEntryFilter.CLASS_DATA }
                            )
                            DexMapFilterButton(
                                filter = DexMapEntryFilter.CODE,
                                selected = mapFilter == DexMapEntryFilter.CODE,
                                onClick = { mapFilter = DexMapEntryFilter.CODE }
                            )
                            DexMapFilterButton(
                                filter = DexMapEntryFilter.DATA,
                                selected = mapFilter == DexMapEntryFilter.DATA,
                                onClick = { mapFilter = DexMapEntryFilter.DATA }
                            )
                        }
                        Text(
                            text = stringResource(
                                Strings.hex_dex_map_filter_count,
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
                            text = stringResource(Strings.hex_dex_map_empty),
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
                                    "${entry.index}-${entry.type}-${entry.offset}"
                                }
                            ) { index ->
                                DexMapEntryRow(
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
internal fun DexMapFilterButton(
    filter: DexMapEntryFilter,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(
            text = dexMapFilterLabel(filter),
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
internal fun DexMapEntryRow(
    entry: HexDexMapEntry,
    onGotoOffset: (Long) -> Unit
) {
    Surface(
        onClick = { onGotoOffset(entry.offset) },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = entry.typeName,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    Strings.hex_dex_map_meta,
                    entry.index,
                    entry.type,
                    entry.size,
                    entry.offset,
                    entry.entryFileOffset
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

