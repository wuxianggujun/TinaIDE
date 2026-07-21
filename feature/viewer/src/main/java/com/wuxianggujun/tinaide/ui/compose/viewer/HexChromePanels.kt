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
 * Hex viewer header, top action bar, and search panel.
 */

@Composable
internal fun HexHeader() {
    val headerTextColor = MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(AddressColumnWidth)
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(Strings.hex_header_offset),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = headerTextColor
                )
            }

            VerticalDivider()

            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp)
            ) {
                repeat(HexFileDataManager.VISUAL_BYTES_PER_ROW) { column ->
                    if (column == 4) {
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        )
                    }
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = column.toString(),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = headerTextColor
                        )
                    }
                }
            }

            VerticalDivider()

            Box(
                modifier = Modifier
                    .width(AsciiColumnWidth)
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(Strings.hex_header_ascii),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = headerTextColor
                )
            }
        }
    }
}

@Composable
internal fun HexTopActionBar(
    state: HexViewerState,
    analysis: HexBinaryAnalysis?,
    isAnalysisLoading: Boolean,
    isSearchExpanded: Boolean,
    isAnalysisPanelOpen: Boolean,
    onToggleSearch: () -> Unit,
    onToggleAnalysisPanel: () -> Unit,
    onOpenCommands: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(onClick = onToggleSearch) {
                Text(
                    stringResource(
                        if (isSearchExpanded) {
                            Strings.content_desc_collapse
                        } else {
                            Strings.hex_search_label
                        }
                    )
                )
            }
            TextButton(
                onClick = onToggleAnalysisPanel,
                enabled = state.fileSize > 0L
            ) {
                Text(
                    stringResource(
                        if (isAnalysisPanelOpen) {
                            Strings.hex_workbench_hide_analysis_panel
                        } else {
                            Strings.hex_workbench_show_analysis_panel
                        }
                    )
                )
            }
            TextButton(
                onClick = onOpenCommands,
                enabled = state.fileSize > 0L
            ) {
                Text(stringResource(Strings.hex_workbench_commands_title))
            }

            when {
                isAnalysisLoading -> {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(Strings.hex_analysis_loading),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                analysis != null -> {
                    Text(
                        text = stringResource(Strings.hex_analysis_file_kind, hexFileKindLabel(analysis.fileKind)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (state.searchResults.isNotEmpty()) {
                Text(
                    text = stringResource(
                        Strings.hex_search_results_count,
                        state.searchResultIndex + 1,
                        state.searchResults.size
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun HexSearchPanel(
    state: HexViewerState,
    onQueryChange: (String) -> Unit,
    onRunSearch: () -> Unit,
    onPreviousResult: () -> Unit,
    onNextResult: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = onQueryChange,
                    label = { Text(stringResource(Strings.hex_search_label)) },
                    placeholder = { Text(stringResource(Strings.hex_search_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onRunSearch,
                    enabled = !state.isSearchRunning
                ) {
                    Text(stringResource(Strings.hex_search_run))
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = onPreviousResult,
                    enabled = state.searchResults.isNotEmpty()
                ) {
                    Text(stringResource(Strings.hex_search_previous))
                }
                TextButton(
                    onClick = onNextResult,
                    enabled = state.searchResults.isNotEmpty()
                ) {
                    Text(stringResource(Strings.hex_search_next))
                }

                val resultText = when {
                    state.isSearchRunning -> stringResource(Strings.hex_search_running)
                    state.searchResults.isEmpty() && state.searchQuery.isNotBlank() -> {
                        stringResource(Strings.hex_search_results_empty)
                    }
                    state.searchResults.isNotEmpty() -> {
                        stringResource(
                            Strings.hex_search_results_count,
                            state.searchResultIndex + 1,
                            state.searchResults.size
                        )
                    }
                    else -> stringResource(Strings.hex_search_hint)
                }
                Text(
                    text = resultText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                state.searchError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

