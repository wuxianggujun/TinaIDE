package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.ui.runtime.GraphicalRuntimeLogSearch

/** Only query editing owns an IME window; the log and native rendering windows remain unchanged. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun GraphicalRuntimeLogSearchBar(
    search: GraphicalRuntimeLogSearch,
    onClose: () -> Unit,
) {
    if (!search.editingQuery) LogSearchRow(search, false, search::open, {}, onClose)
    else Box(Modifier.fillMaxWidth().height(40.dp))
    if (search.editingQuery) {
        Dialog(
            onDismissRequest = search::finishEditing,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            // Dialog owns keyboard avoidance; native log/runtime window bounds stay intact.
            Surface(
                Modifier.padding(16.dp).widthIn(max = 480.dp).fillMaxWidth().semantics { testTagsAsResourceId = true },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                LogSearchRow(search, true, {}, search::finishEditing, onClose)
            }
        }
    }
}

@Composable
private fun LogSearchRow(
    search: GraphicalRuntimeLogSearch,
    editing: Boolean,
    onEdit: () -> Unit,
    onSubmit: () -> Unit,
    onClose: () -> Unit,
) {
    val hint = stringResource(Strings.floating_overlay_log_search_hint)
    Row(Modifier.fillMaxWidth().height(40.dp).testTag("graphical-log-search-bar"), verticalAlignment = Alignment.CenterVertically) {
        if (editing) {
            val focus = remember { FocusRequester() }
            val keyboard = LocalSoftwareKeyboardController.current
            LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
            DisposableEffect(Unit) { onDispose { keyboard?.hide() } }
            BasicTextField(
                value = search.query,
                onValueChange = search::updateQuery,
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp).focusRequester(focus).testTag("graphical-log-search-input"),
                textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search.revealCurrent(); onSubmit() }),
                decorationBox = { inner ->
                    Box {
                        if (search.query.isEmpty()) Text(hint, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        inner()
                    }
                },
            )
        } else {
            Text(
                search.query.ifEmpty { hint },
                modifier = Modifier.weight(1f).clickable(onClick = onEdit).padding(horizontal = 6.dp).testTag("graphical-log-search-query"),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            stringResource(
                if (search.capped) Strings.floating_overlay_log_search_count_capped else Strings.floating_overlay_log_search_count,
                search.currentIndex + 1, search.matchCount,
            ),
            modifier = Modifier.testTag("graphical-log-search-count"),
            style = MaterialTheme.typography.labelSmall,
            color = if (search.matchCount == 0 && search.query.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        IconButton(onClick = { search.move(false) }, enabled = search.matchCount > 0,
            modifier = Modifier.size(32.dp).testTag("graphical-log-search-previous")) {
            Icon(Icons.Default.KeyboardArrowUp, stringResource(Strings.content_desc_previous), Modifier.size(20.dp))
        }
        IconButton(onClick = { search.move(true) }, enabled = search.matchCount > 0,
            modifier = Modifier.size(32.dp).testTag("graphical-log-search-next")) {
            Icon(Icons.Default.KeyboardArrowDown, stringResource(Strings.content_desc_next), Modifier.size(20.dp))
        }
        IconButton(onClick = onClose, modifier = Modifier.size(32.dp).testTag("graphical-log-search-close")) {
            Icon(Icons.Default.Close, stringResource(Strings.btn_close), Modifier.size(20.dp))
        }
    }
}
