package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wuxianggujun.tinaide.core.i18n.Strings

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BottomPanelTabRow(
    selectedTab: BottomPanelTab,
    onTabSelected: (BottomPanelTab) -> Unit,
    modifier: Modifier = Modifier,
    tabs: List<BottomPanelTab> = BottomPanelTab.entries,
    badges: Map<BottomPanelTab, Int> = emptyMap(),
    actions: Map<BottomPanelTab, List<BottomPanelTabMenuAction>> = emptyMap(),
    overflowTabs: List<BottomPanelTab> = emptyList(),
    isNearFullScreen: Boolean = false,
    onToggleFullScreen: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null
) {
    val selectedTabIndex = tabs.indexOf(selectedTab).let { if (it >= 0) it else 0 }
    var overflowExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(BottomPanelTabRowHeight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PrimaryScrollableTabRow(
            selectedTabIndex = selectedTabIndex,
            modifier = Modifier.weight(1f),
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            edgePadding = 8.dp,
            divider = {}
        ) {
            tabs.forEach { tab ->
                val tabActions = actions[tab].orEmpty()
                var menuExpanded by remember(tab) { mutableStateOf(false) }

                Box {
                    BottomPanelTabItem(
                        tab = tab,
                        selected = selectedTab == tab,
                        badgeText = formatBottomPanelTabBadgeCount(badges[tab] ?: 0),
                        onClick = { onTabSelected(tab) },
                        onLongClick = tabActions
                            .takeIf { it.isNotEmpty() }
                            ?.let {
                                {
                                    onTabSelected(tab)
                                    menuExpanded = true
                                }
                            }
                    )

                    BottomPanelTabActionMenu(
                        expanded = menuExpanded,
                        actions = tabActions,
                        onDismiss = { menuExpanded = false }
                    )
                }
            }
        }

        if (overflowTabs.isNotEmpty() || onToggleFullScreen != null || onClose != null) {
            Spacer(modifier = Modifier.width(6.dp))
        }

        if (overflowTabs.isNotEmpty()) {
            Box {
                TinaPanelSegmentButton(
                    onClick = { overflowExpanded = true },
                    modifier = Modifier.size(32.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(Strings.content_desc_more_options),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                TinaDropdownMenu(
                    expanded = overflowExpanded,
                    onDismissRequest = { overflowExpanded = false },
                ) {
                    overflowTabs.forEach { tab ->
                        TinaDropdownMenuItem(
                            text = { Text(stringResource(tab.titleRes)) },
                            onClick = {
                                overflowExpanded = false
                                onTabSelected(tab)
                            },
                        )
                    }
                }
            }

            if (onToggleFullScreen != null || onClose != null) {
                Spacer(modifier = Modifier.width(4.dp))
            }
        }

        if (onToggleFullScreen != null) {
            TinaPanelSegmentButton(
                onClick = onToggleFullScreen,
                modifier = Modifier.size(32.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(
                    imageVector = if (isNearFullScreen) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                    contentDescription = if (isNearFullScreen) {
                        stringResource(Strings.content_desc_restore_panel)
                    } else {
                        stringResource(Strings.content_desc_expand_fullscreen)
                    },
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (onClose != null) {
            TinaPanelSegmentButton(
                onClick = onClose,
                modifier = Modifier.size(32.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(Strings.content_desc_close_panel),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BottomPanelTabItem(
    tab: BottomPanelTab,
    selected: Boolean,
    badgeText: String?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?
) {
    val interactionSource = remember { MutableInteractionSource() }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .height(BottomPanelTabRowHeight)
            .widthIn(min = 72.dp)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(tab.titleRes),
                fontSize = 13.sp,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (badgeText != null) {
                Badge(
                    containerColor = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColor = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                ) {
                    Text(
                        text = badgeText,
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomPanelTabActionMenu(
    expanded: Boolean,
    actions: List<BottomPanelTabMenuAction>,
    onDismiss: () -> Unit
) {
    TinaDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        var dividerInserted = false
        actions.forEach { action ->
            if (action.destructive && !dividerInserted) {
                TinaDropdownMenuDivider()
                dividerInserted = true
            }

            val onActionClick = {
                onDismiss()
                action.onClick()
            }

            if (action.destructive) {
                TinaDropdownMenuDangerItem(
                    text = { Text(stringResource(action.titleRes)) },
                    enabled = action.enabled,
                    onClick = onActionClick
                )
            } else {
                TinaDropdownMenuItem(
                    text = { Text(stringResource(action.titleRes)) },
                    enabled = action.enabled,
                    onClick = onActionClick
                )
            }
        }
    }
}
