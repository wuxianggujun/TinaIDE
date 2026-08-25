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
 * Entropy bucket bar and entropy dialogs.
 */

@Composable
internal fun EntropyBucketBar(
    bucket: HexEntropyVisualBucket,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(18.dp)
            .height(34.dp),
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 3.dp, vertical = 3.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp * bucket.normalizedHeight)
                    .background(entropyBucketColor(bucket.level))
            )
        }
    }
}

@Composable
internal fun entropyBucketColor(level: HexEntropyLevel): Color = when (level) {
    HexEntropyLevel.LOW -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    HexEntropyLevel.MEDIUM -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.75f)
    HexEntropyLevel.HIGH -> MaterialTheme.colorScheme.error.copy(alpha = 0.9f)
}

@Composable
internal fun EntropyBucketsDialog(
    buckets: List<HexEntropyVisualBucket>,
    onDismiss: () -> Unit,
    onGotoOffset: (Long) -> Unit,
    onMarkOffsets: (List<Long>) -> Unit
) {
    var bucketFilter by remember(buckets) { mutableStateOf(EntropyBucketFilter.ALL) }
    val filteredBuckets = remember(buckets, bucketFilter) {
        filterEntropyVisualBuckets(
            buckets = buckets,
            filter = bucketFilter
        )
    }

    TinaAlertDialog(
        onDismissRequest = onDismiss,
        title = { TinaDialogTitleText(stringResource(Strings.hex_entropy_dialog_title)) },
        text = {
            TinaDialogContentColumn {
                TinaDialogCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            EntropyBucketFilterButton(
                                filter = EntropyBucketFilter.ALL,
                                selected = bucketFilter == EntropyBucketFilter.ALL,
                                onClick = { bucketFilter = EntropyBucketFilter.ALL }
                            )
                            EntropyBucketFilterButton(
                                filter = EntropyBucketFilter.LOW,
                                selected = bucketFilter == EntropyBucketFilter.LOW,
                                onClick = { bucketFilter = EntropyBucketFilter.LOW }
                            )
                            EntropyBucketFilterButton(
                                filter = EntropyBucketFilter.MEDIUM,
                                selected = bucketFilter == EntropyBucketFilter.MEDIUM,
                                onClick = { bucketFilter = EntropyBucketFilter.MEDIUM }
                            )
                            EntropyBucketFilterButton(
                                filter = EntropyBucketFilter.HIGH,
                                selected = bucketFilter == EntropyBucketFilter.HIGH,
                                onClick = { bucketFilter = EntropyBucketFilter.HIGH }
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(
                                    Strings.hex_entropy_filter_count,
                                    filteredBuckets.size,
                                    buckets.size
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = { onMarkOffsets(filteredBuckets.map { bucket -> bucket.startOffset }) },
                                enabled = filteredBuckets.isNotEmpty()
                            ) {
                                Text(stringResource(Strings.hex_bookmark_mark_visible))
                            }
                        }
                    }
                }
                TinaDialogCard {
                    if (filteredBuckets.isEmpty()) {
                        Text(
                            text = stringResource(Strings.hex_entropy_empty),
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
                                count = filteredBuckets.size,
                                key = { index ->
                                    val bucket = filteredBuckets[index]
                                    "${bucket.startOffset}-${bucket.endOffset}-${bucket.level}"
                                }
                            ) { index ->
                                EntropyBucketRow(
                                    bucket = filteredBuckets[index],
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
internal fun EntropyBucketFilterButton(
    filter: EntropyBucketFilter,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(
            text = entropyBucketFilterLabel(filter),
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
internal fun EntropyBucketRow(
    bucket: HexEntropyVisualBucket,
    onGotoOffset: (Long) -> Unit,
    onMarkOffset: (Long) -> Unit
) {
    Surface(
        onClick = { onGotoOffset(bucket.startOffset) },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(28.dp)
                    .background(entropyBucketColor(bucket.level))
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(
                        Strings.hex_entropy_bucket_meta,
                        bucket.startOffset,
                        bucket.endOffset,
                        entropyLevelLabel(bucket.level),
                        bucket.entropy
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { onMarkOffset(bucket.startOffset) }) {
                Text(stringResource(Strings.hex_bookmark_mark))
            }
        }
    }
}

