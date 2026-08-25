package com.wuxianggujun.tinaide.ui.compose.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.commands.HostCommandExecutor
import com.wuxianggujun.tinaide.core.git.GitStatus
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.plugin.PluginManager
import com.wuxianggujun.tinaide.ui.compose.icons.TinaTabIcons
import com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerState
import java.io.File
import me.rerere.rikkahub.RikkaHubEmbeddedChatPane

internal class DrawerFileCallbacks(
    val onFileClick: (File) -> Unit,
    val onContextAction: (FileContextAction) -> Unit,
    val onAddFileClick: (File?) -> Unit,
)

internal class DrawerGitCallbacks(
    val onRefresh: () -> Unit,
    val onStageAll: () -> Unit,
    val onUnstageAll: () -> Unit = {},
    val onCommitWithMessage: (String) -> Unit = {},
    val onStageFile: (String) -> Unit,
    val onUnstageFile: (String) -> Unit,
    val onDiscardChanges: (String) -> Unit,
    val onFileClick: (String) -> Unit,
    val onShowDiff: (String, Boolean) -> Unit,
    val onInitRepository: () -> Unit = {},
    val onOpenSyncDialog: () -> Unit = {},
    val onOpenRemoteDialog: () -> Unit = {},
    val recentCommitMessages: List<String> = emptyList(),
    val onClearCommitMessageHistory: () -> Unit = {},
)

@Composable
internal fun DrawerContent(
    projectName: String,
    fileTreeState: FileTreeState,
    pluginManager: PluginManager,
    fileCallbacks: DrawerFileCallbacks,
    gitStatus: GitStatus,
    gitIsLoading: Boolean,
    gitStatusMap: Map<String, FileGitStatus>,
    gitCallbacks: DrawerGitCallbacks,
    editorContainerState: EditorContainerState? = null,
    modifier: Modifier = Modifier,
    hostCommandExecutor: HostCommandExecutor? = null,
    drawerOpen: Boolean = true,
    selectedDrawerTab: DrawerTab? = null,
    onDrawerTabSelected: ((DrawerTab) -> Unit)? = null,
) {
    // 未外部控制时保留本地 Tab，兼容预览/单测
    var localDrawerTab by remember { mutableStateOf(DrawerTab.FILES) }
    val drawerTab = selectedDrawerTab ?: localDrawerTab
    val setDrawerTab: (DrawerTab) -> Unit = { tab ->
        if (onDrawerTabSelected != null) {
            onDrawerTabSelected(tab)
        } else {
            localDrawerTab = tab
        }
    }
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val keyboardVisible = WindowInsets.ime.getBottom(density) > 0
    val showDrawerTabBar = drawerTab != DrawerTab.RIKKAHUB || !keyboardVisible

    RikkaHubDrawerSoftInputModeEffect(
        activity = activity,
        enabled = drawerOpen && drawerTab == DrawerTab.RIKKAHUB
    )

    Column(modifier = modifier.fillMaxSize()) {
        if (drawerTab != DrawerTab.RIKKAHUB) {
            DrawerHeader(
                drawerTab = drawerTab,
                projectName = projectName,
                gitStatus = gitStatus,
                gitIsLoading = gitIsLoading,
                onAddFileClick = {
                    val targetDir = fileTreeState.selectedDirectoryPath?.let(::File)
                    fileCallbacks.onAddFileClick(targetDir)
                },
                onGitRefresh = gitCallbacks.onRefresh
            )

            HorizontalDivider()
        }

        when (drawerTab) {
            DrawerTab.FILES -> {
                FileTree(
                    state = fileTreeState,
                    pluginManager = pluginManager,
                    hostCommandExecutor = hostCommandExecutor,
                    onFileClick = fileCallbacks.onFileClick,
                    onFileLongClick = { },
                    onContextAction = fileCallbacks.onContextAction,
                    gitStatusMap = gitStatusMap,
                    modifier = Modifier.weight(1f)
                )
            }

            DrawerTab.SYMBOLS -> {
                if (editorContainerState != null) {
                    OutlineContent(
                        editorContainerState = editorContainerState,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    EmptyStateContent(
                        message = stringResource(Strings.bottom_panel_outline),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            DrawerTab.GIT -> {
                DrawerGitPanelContent(
                    status = gitStatus,
                    onStageFile = gitCallbacks.onStageFile,
                    onUnstageFile = gitCallbacks.onUnstageFile,
                    onStageAll = gitCallbacks.onStageAll,
                    onUnstageAll = gitCallbacks.onUnstageAll,
                    onDiscardChanges = gitCallbacks.onDiscardChanges,
                    onFileClick = gitCallbacks.onFileClick,
                    onShowDiff = gitCallbacks.onShowDiff,
                    onCommit = gitCallbacks.onCommitWithMessage,
                    onInitRepository = gitCallbacks.onInitRepository,
                    onOpenSync = gitCallbacks.onOpenSyncDialog,
                    onOpenRemotes = gitCallbacks.onOpenRemoteDialog,
                    recentCommitMessages = gitCallbacks.recentCommitMessages,
                    onClearCommitMessageHistory = gitCallbacks.onClearCommitMessageHistory,
                    modifier = Modifier.weight(1f)
                )
            }

            DrawerTab.RIKKAHUB -> {
                Box(modifier = Modifier.weight(1f)) {
                    RikkaHubEmbeddedChatPane(
                        modifier = Modifier.fillMaxSize()
                    )
                    if (keyboardVisible) {
                        RikkaHubKeyboardDismissButton(
                            onClick = {
                                focusManager.clearFocus(force = true)
                                keyboardController?.hide()
                            },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                        )
                    }
                }
            }
        }

        if (showDrawerTabBar) {
            HorizontalDivider()

            DrawerTabBar(
                selectedTab = drawerTab,
                onTabSelected = setDrawerTab,
            )
        }
    }
}

@Composable
private fun RikkaHubKeyboardDismissButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = stringResource(Strings.content_desc_hide_keyboard),
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun RikkaHubDrawerSoftInputModeEffect(
    activity: Activity?,
    enabled: Boolean,
) {
    DisposableEffect(activity, enabled) {
        if (activity == null || !enabled) {
            onDispose { }
        } else {
            val window = activity.window
            val originalMode = window.attributes.softInputMode
            val adjustedMode =
                (originalMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            window.setSoftInputMode(adjustedMode)

            onDispose {
                window.setSoftInputMode(originalMode)
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun DrawerHeader(
    drawerTab: DrawerTab,
    projectName: String,
    gitStatus: GitStatus,
    gitIsLoading: Boolean,
    onAddFileClick: () -> Unit,
    onGitRefresh: () -> Unit
) {
    if (drawerTab == DrawerTab.RIKKAHUB) return

    val actionIconButtonColors = IconButtonDefaults.iconButtonColors(
        contentColor = MaterialTheme.colorScheme.onSurface
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (drawerTab == DrawerTab.FILES) {
            ProjectIcon(
                projectName = projectName,
                size = 36.dp
            )
            Spacer(modifier = Modifier.width(12.dp))
        }

        val headerTitle = when (drawerTab) {
            DrawerTab.FILES -> projectName
            DrawerTab.SYMBOLS -> stringResource(Strings.drawer_tab_symbols_title)
            DrawerTab.GIT -> stringResource(Strings.drawer_title_source_control)
            DrawerTab.RIKKAHUB -> ""
        }
        Text(
            text = headerTitle,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )

        when (drawerTab) {
            DrawerTab.FILES -> {
                IconButton(
                    onClick = onAddFileClick,
                    colors = actionIconButtonColors
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(Strings.content_desc_add_file)
                    )
                }
            }

            DrawerTab.SYMBOLS -> Unit

            DrawerTab.GIT -> {
                IconButton(
                    onClick = onGitRefresh,
                    enabled = !gitIsLoading,
                    colors = actionIconButtonColors
                ) {
                    if (gitIsLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(Strings.menu_refresh),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            DrawerTab.RIKKAHUB -> Unit
        }
    }
}

@Composable
private fun DrawerTabBar(
    selectedTab: DrawerTab,
    onTabSelected: (DrawerTab) -> Unit
) {
    val selectedIconBackgroundShape = RoundedCornerShape(12.dp)
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        NavigationBar(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
            windowInsets = WindowInsets(0)
        ) {
            DrawerTab.entries.forEach { tab ->
                val selected = selectedTab == tab
                val icon = when (tab) {
                    DrawerTab.FILES -> TinaTabIcons.Files
                    DrawerTab.SYMBOLS -> TinaTabIcons.Symbols
                    DrawerTab.GIT -> TinaTabIcons.Git
                    DrawerTab.RIKKAHUB -> TinaTabIcons.RikkaHub
                }
                val tabTitle = stringResource(tab.titleRes)

                NavigationBarItem(
                    selected = selected,
                    onClick = { onTabSelected(tab) },
                    // 四 Tab 略收边距，避免挤；不搞单独「极窄布局」
                    icon = {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(selectedIconBackgroundShape)
                                .background(
                                    if (selected) {
                                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f)
                                    } else {
                                        Color.Transparent
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = tabTitle,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    },
                    label = {
                        Text(
                            text = tabTitle,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        indicatorColor = Color.Transparent,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }
    }
}
