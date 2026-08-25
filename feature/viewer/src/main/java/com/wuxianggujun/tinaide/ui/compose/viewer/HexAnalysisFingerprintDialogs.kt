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
 * Fingerprint, byte frequency, magic signature dialogs.
 */

@Composable
internal fun HexFingerprintDialog(
    fingerprint: HexFileFingerprint,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val crc32Text = stringResource(Strings.hex_fingerprint_crc32_value, fingerprint.crc32)

    fun copyFingerprint(value: String) {
        scope.launch {
            clipboard.setClipEntry(
                ClipData.newPlainText("hex-fingerprint", value).toClipEntry()
            )
        }
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_fingerprint_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(
                                Strings.hex_fingerprint_size,
                                fingerprint.byteCount,
                                formatFileSize(fingerprint.byteCount)
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
                        HexFingerprintRow(
                            label = stringResource(Strings.hex_fingerprint_sha256),
                            value = fingerprint.sha256,
                            onCopy = ::copyFingerprint
                        )
                        HexFingerprintRow(
                            label = stringResource(Strings.hex_fingerprint_sha1),
                            value = fingerprint.sha1,
                            onCopy = ::copyFingerprint
                        )
                        HexFingerprintRow(
                            label = stringResource(Strings.hex_fingerprint_md5),
                            value = fingerprint.md5,
                            onCopy = ::copyFingerprint
                        )
                        HexFingerprintRow(
                            label = stringResource(Strings.hex_fingerprint_crc32),
                            value = crc32Text,
                            onCopy = ::copyFingerprint
                        )
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
internal fun HexFingerprintRow(
    label: String,
    value: String,
    onCopy: (String) -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = { onCopy(value) }) {
                Text(stringResource(Strings.action_copy))
            }
        }
    }
}

@Composable
internal fun HexByteFrequencyDialog(
    byteFrequency: HexByteFrequencySummary,
    onDismiss: () -> Unit
) {
    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_byte_frequency_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(
                                Strings.hex_byte_frequency_summary,
                                byteFrequency.totalBytes,
                                byteFrequency.uniqueByteValues
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(
                                Strings.hex_byte_frequency_zero_ff_ratio,
                                byteFrequency.zeroBytes.percentOf(byteFrequency.totalBytes),
                                byteFrequency.ffBytes.percentOf(byteFrequency.totalBytes)
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(
                                Strings.hex_byte_frequency_ascii_ratio,
                                byteFrequency.printableAsciiBytes.percentOf(byteFrequency.totalBytes)
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(
                                Strings.hex_byte_frequency_control_ratio,
                                byteFrequency.controlBytes.percentOf(byteFrequency.totalBytes)
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
                        Text(
                            text = stringResource(Strings.hex_byte_frequency_top_title),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (byteFrequency.topBytes.isEmpty()) {
                            Text(
                                text = stringResource(Strings.hex_byte_frequency_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(280.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(
                                    count = byteFrequency.topBytes.size,
                                    key = { index -> byteFrequency.topBytes[index].byteValue }
                                ) { index ->
                                    HexByteFrequencyRow(entry = byteFrequency.topBytes[index])
                                }
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
internal fun HexByteFrequencyRow(entry: HexByteFrequencyEntry) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = entry.byteValue.toHexByteLabel(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(58.dp)
            )
            Text(
                text = stringResource(
                    Strings.hex_byte_frequency_row,
                    entry.count,
                    entry.ratio * 100.0
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
internal fun HexRepeatedByteRunsDialog(
    runs: List<HexRepeatedByteRun>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit,
    onMarkOffsets: (List<Long>) -> Unit
) {
    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_repeated_runs_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(
                                    Strings.hex_repeated_runs_summary,
                                    runs.size,
                                    runs.maxOfOrNull { it.length } ?: 0L
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = { onMarkOffsets(runs.map { run -> run.startOffset }) },
                                enabled = runs.isNotEmpty()
                            ) {
                                Text(stringResource(Strings.hex_bookmark_mark_all))
                            }
                        }
                    }
                }
                TinaDialogCard {
                    if (runs.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_repeated_runs_empty),
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
                                count = runs.size,
                                key = { index ->
                                    val run = runs[index]
                                    "${run.byteValue}-${run.startOffset}-${run.length}"
                                }
                            ) { index ->
                                HexRepeatedByteRunRow(
                                    run = runs[index],
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
internal fun HexRepeatedByteRunRow(
    run: HexRepeatedByteRun,
    onGotoOffset: (Long) -> Unit,
    onMarkOffset: (Long) -> Unit
) {
    Surface(
        onClick = { onGotoOffset(run.startOffset) },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = run.byteValue.toHexByteLabel(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(58.dp)
            )
            Text(
                text = stringResource(
                    Strings.hex_repeated_run_meta,
                    run.startOffset,
                    run.length
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { onMarkOffset(run.startOffset) }) {
                Text(stringResource(Strings.hex_bookmark_mark))
            }
        }
    }
}

@Composable
internal fun HexMagicSignaturesDialog(
    matches: List<HexMagicSignatureMatch>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit,
    onMarkOffsets: (List<Long>) -> Unit
) {
    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_magic_signatures_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(Strings.hex_magic_signatures_summary, matches.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { onMarkOffsets(matches.map { match -> match.offset }) },
                            enabled = matches.isNotEmpty()
                        ) {
                            Text(stringResource(Strings.hex_bookmark_mark_all))
                        }
                    }
                }
                TinaDialogCard {
                    if (matches.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_magic_signatures_empty),
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
                                count = matches.size,
                                key = { index ->
                                    val match = matches[index]
                                    "${match.kind}-${match.offset}-${match.signatureLength}"
                                }
                            ) { index ->
                                HexMagicSignatureRow(
                                    match = matches[index],
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
internal fun HexMagicSignatureRow(
    match: HexMagicSignatureMatch,
    onGotoOffset: (Long) -> Unit,
    onMarkOffset: (Long) -> Unit
) {
    Surface(
        onClick = { onGotoOffset(match.offset) },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = hexMagicSignatureKindLabel(match.kind),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(136.dp)
            )
            Text(
                text = stringResource(
                    Strings.hex_magic_signature_meta,
                    match.offset,
                    match.signatureLength
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { onMarkOffset(match.offset) }) {
                Text(stringResource(Strings.hex_bookmark_mark))
            }
        }
    }
}

@Composable
internal fun hexMagicSignatureKindLabel(kind: HexMagicSignatureKind): String = stringResource(
    when (kind) {
        HexMagicSignatureKind.ELF -> Strings.hex_magic_signature_kind_elf
        HexMagicSignatureKind.DEX -> Strings.hex_magic_signature_kind_dex
        HexMagicSignatureKind.ZIP_LOCAL_FILE -> Strings.hex_magic_signature_kind_zip_local
        HexMagicSignatureKind.ZIP_CENTRAL_DIRECTORY -> Strings.hex_magic_signature_kind_zip_central
        HexMagicSignatureKind.ZIP_EOCD -> Strings.hex_magic_signature_kind_zip_eocd
        HexMagicSignatureKind.PNG -> Strings.hex_magic_signature_kind_png
        HexMagicSignatureKind.JPEG -> Strings.hex_magic_signature_kind_jpeg
        HexMagicSignatureKind.ANDROID_RESOURCES -> Strings.hex_magic_signature_kind_android_resources
        HexMagicSignatureKind.SQLITE -> Strings.hex_magic_signature_kind_sqlite
    }
)
