package com.wuxianggujun.tinaide.ui.compose.screens.main

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wuxianggujun.tinaide.core.commands.HostCommandAvailability
import com.wuxianggujun.tinaide.core.commands.HostCommandCatalog
import com.wuxianggujun.tinaide.core.commands.HostCommandDescriptor
import com.wuxianggujun.tinaide.core.commands.HostCommandExecutor
import com.wuxianggujun.tinaide.core.commands.HostCommandInvocation
import com.wuxianggujun.tinaide.core.commands.HostCommands
import com.wuxianggujun.tinaide.core.compile.BuildSystem
import com.wuxianggujun.tinaide.core.config.ShortcutAction
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.plugin.InstalledPlugin
import com.wuxianggujun.tinaide.plugin.PluginManager
import com.wuxianggujun.tinaide.plugin.ResolvedPluginCommand
import com.wuxianggujun.tinaide.plugin.ResolvedPluginCommandSource
import com.wuxianggujun.tinaide.plugin.script.api.PluginCommandRegistry
import com.wuxianggujun.tinaide.project.ProjectApkExportType
import com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerState
import java.io.File
import com.wuxianggujun.tinaide.ui.compose.state.editor.SplitEditorLayout

private const val COMMAND_REBUILD_RUN = "project.rebuildRun"
private const val COMMAND_RUN_TERMINAL = "project.runTerminal"
private const val COMMAND_DEBUG = "project.debug"
private const val COMMAND_PACKAGE_APK = "project.packageApk"
private const val COMMAND_GLOBAL_SEARCH = "view.globalSearch"
private const val COMMAND_WORKSPACE_EXIT = HostCommands.PROJECT_CLOSE

/**
 * 溢出菜单精选项（顺序即分组顺序：文件 → 视图 → 工程）。
 */
private val DEFAULT_OVERFLOW_MENU_COMMAND_IDS = listOf(
    // 文件
    HostCommands.EDITOR_SAVE_ALL,
    HostCommands.EDITOR_FORMAT,
    // 视图 / 导航
    HostCommands.VIEW_COMMAND_PALETTE,
    COMMAND_GLOBAL_SEARCH,
    HostCommands.VIEW_BOOKMARKS,
    HostCommands.VIEW_TOGGLE_TERMINAL,
    HostCommands.VIEW_SETTINGS,
    // 工程
    COMMAND_WORKSPACE_EXIT,
)

internal data class MainActivityCommandAvailability(
    val hasActiveFile: Boolean,
    val isCompiling: Boolean,
    val isDirty: Boolean,
    val canPackageApk: Boolean,
    val isBasicLspNavigationAvailable: Boolean,
    val isAdvancedLspNavigationAvailable: Boolean,
    val isCallHierarchyIncomingAvailable: Boolean,
    val isLspRefactorAvailable: Boolean,
    val isHeaderSourceSwitchAvailable: Boolean,
    val canNavigateBack: Boolean,
    val canNavigateForward: Boolean,
    val isSplitEditorEnabled: Boolean,
    val splitEditorLayout: SplitEditorLayout,
    val canMoveTabToSecondaryPane: Boolean,
    val canCopyTabToSecondaryPane: Boolean,
    val currentBuildSystem: BuildSystem,
)

internal fun resolveCanPackageApk(buildUiState: MainActivityBuildUiState): Boolean = when (buildUiState.apkExportType) {
    null,
    ProjectApkExportType.DISABLED -> false

    ProjectApkExportType.TERMINAL -> buildUiState.hasTerminalApkExportOptions
    else -> true
}

@Composable
internal fun rememberMainActivityCommands(
    availability: MainActivityCommandAvailability,
    activeFile: File?,
    isActiveTabDirty: Boolean,
    callbacks: TopBarCallbacks,
    hostCommandExecutor: HostCommandExecutor?,
): List<MainActivityCommand> {
    val context = LocalContext.current
    val pluginManager = remember(context) {
        PluginManager.getInstance(context.applicationContext)
    }
    val enabledPlugins by pluginManager.enabledPluginsFlow.collectAsStateWithLifecycle()
    val pluginCommands = remember(
        pluginManager,
        enabledPlugins,
        activeFile,
        isActiveTabDirty,
        hostCommandExecutor,
    ) {
        resolvePluginEditorToolbarCommands(
            pluginManager = pluginManager,
            enabledPlugins = enabledPlugins,
            activeFile = activeFile,
            isDirty = isActiveTabDirty,
            hostCommandExecutor = hostCommandExecutor,
        )
    }

    return remember(
        availability,
        callbacks,
        pluginCommands,
    ) {
        buildMainActivityCommands(
            availability = availability,
            callbacks = callbacks,
            pluginCommands = pluginCommands
        )
    }
}

@Composable
internal fun rememberMainActivityOverflowCommands(
    commands: List<MainActivityCommand>,
): List<MainActivityCommand> = remember(commands) {
    selectMainActivityOverflowCommands(commands)
}

internal fun selectMainActivityOverflowCommands(
    commands: List<MainActivityCommand>,
): List<MainActivityCommand> {
    val commandById = commands.associateBy(MainActivityCommand::id)
    return DEFAULT_OVERFLOW_MENU_COMMAND_IDS.mapNotNull(commandById::get)
}

private fun buildMainActivityCommands(
    availability: MainActivityCommandAvailability,
    callbacks: TopBarCallbacks,
    pluginCommands: List<MainActivityCommand>,
): List<MainActivityCommand> = buildList {
    addHostCommand(
        id = HostCommands.EDITOR_SAVE,
        availability = availability,
        execute = callbacks.onSave
    )
    addHostCommand(
        id = HostCommands.EDITOR_SAVE_ALL,
        availability = availability,
        execute = callbacks.onSaveAll
    )
    addHostCommand(
        id = HostCommands.EDITOR_FORMAT,
        availability = availability,
        execute = callbacks.onFormatCode
    )
    addHostCommand(
        id = HostCommands.EDITOR_GOTO_LINE,
        availability = availability,
        execute = callbacks.onGotoLine
    )

    addHostCommand(
        id = HostCommands.PROJECT_BUILD,
        availability = availability,
        execute = callbacks.onBuild
    )
    addHostCommand(
        id = HostCommands.PROJECT_RUN,
        availability = availability,
        execute = callbacks.onCompile
    )
    addBuiltInCommand(
        id = COMMAND_REBUILD_RUN,
        titleRes = Strings.action_rebuild_and_run,
        category = MainActivityCommandCategory.BUILD,
        enabled = !availability.isCompiling,
        keywords = listOf("rebuild", "run"),
        execute = callbacks.onRebuildAndRun
    )
    addBuiltInCommand(
        id = COMMAND_RUN_TERMINAL,
        titleRes = Strings.action_run_in_terminal,
        category = MainActivityCommandCategory.BUILD,
        enabled = !availability.isCompiling,
        keywords = listOf("terminal", "run"),
        execute = callbacks.onCompileInTerminal
    )
    addBuiltInCommand(
        id = COMMAND_DEBUG,
        titleRes = Strings.content_desc_debug,
        category = MainActivityCommandCategory.BUILD,
        enabled = !availability.isCompiling,
        keywords = listOf("debug"),
        execute = callbacks.onDebug
    )
    addBuiltInCommand(
        id = COMMAND_PACKAGE_APK,
        titleRes = Strings.menu_package_apk,
        category = MainActivityCommandCategory.BUILD,
        enabled = availability.canPackageApk,
        keywords = listOf("apk", "package"),
        execute = callbacks.onPackageApk
    )

    if (availability.currentBuildSystem == BuildSystem.CMAKE) {
        addBuiltInCommand(
            id = "project.cmake.openArtifacts",
            titleRes = Strings.menu_cmake_open_artifacts_dir,
            category = MainActivityCommandCategory.BUILD,
            keywords = listOf("cmake", "artifact"),
            execute = callbacks.onCmakeOpenArtifactsDir
        )
        addBuiltInCommand(
            id = "project.cmake.reconfigure",
            titleRes = Strings.menu_cmake_reconfigure,
            category = MainActivityCommandCategory.BUILD,
            keywords = listOf("cmake", "configure"),
            execute = callbacks.onCmakeReconfigure
        )
        addBuiltInCommand(
            id = "project.cmake.cleanReconfigure",
            titleRes = Strings.menu_cmake_clean_and_reconfigure,
            category = MainActivityCommandCategory.BUILD,
            keywords = listOf("cmake", "clean", "reconfigure"),
            execute = callbacks.onCmakeCleanAndReconfigure
        )
        addBuiltInCommand(
            id = "project.cmake.clearBuildDir",
            titleRes = Strings.menu_cmake_clear_build_dir,
            category = MainActivityCommandCategory.BUILD,
            keywords = listOf("cmake", "build dir"),
            execute = callbacks.onCmakeClearBuildDir
        )
    }

    addHostCommand(
        id = HostCommands.EDITOR_NAVIGATE_BACK,
        availability = availability,
        execute = callbacks.onNavigateBack
    )
    addHostCommand(
        id = HostCommands.EDITOR_NAVIGATE_FORWARD,
        availability = availability,
        execute = callbacks.onNavigateForward
    )

    addHostCommand(
        id = HostCommands.EDITOR_PEEK_DEFINITION,
        availability = availability,
        execute = callbacks.onPeekDefinition
    )
    addHostCommand(
        id = HostCommands.EDITOR_GOTO_DEFINITION,
        availability = availability,
        execute = callbacks.onGotoDefinition
    )
    addHostCommand(
        id = HostCommands.EDITOR_FIND_REFERENCES,
        availability = availability,
        execute = callbacks.onFindReferences
    )

    addHostCommand(
        id = HostCommands.EDITOR_GOTO_TYPE_DEFINITION,
        availability = availability,
        execute = callbacks.onGotoTypeDefinition
    )
    addHostCommand(
        id = HostCommands.EDITOR_GOTO_IMPLEMENTATION,
        availability = availability,
        execute = callbacks.onGotoImplementation
    )

    addBuiltInCommand(
        id = "editor.callHierarchyIncoming",
        titleRes = Strings.lsp_call_hierarchy_incoming,
        category = MainActivityCommandCategory.CODE,
        enabled = availability.isCallHierarchyIncomingAvailable,
        keywords = listOf("call hierarchy"),
        execute = callbacks.onCallHierarchyIncoming
    )

    addHostCommand(
        id = HostCommands.EDITOR_CODE_ACTIONS,
        availability = availability,
        execute = callbacks.onCodeActions
    )
    addHostCommand(
        id = HostCommands.EDITOR_RENAME_SYMBOL,
        availability = availability,
        execute = callbacks.onRenameSymbol
    )

    addHostCommand(
        id = HostCommands.EDITOR_SWITCH_HEADER_SOURCE,
        availability = availability,
        execute = callbacks.onSwitchHeaderSource
    )

    addBuiltInCommand(
        id = "view.split.toggle",
        titleRes = if (availability.isSplitEditorEnabled) {
            Strings.menu_disable_split_editor
        } else {
            Strings.menu_enable_split_editor
        },
        category = MainActivityCommandCategory.VIEW,
        keywords = listOf("split"),
        execute = callbacks.onToggleSplitEditor
    )
    if (availability.isSplitEditorEnabled) {
        addBuiltInCommand(
            id = "view.split.horizontal",
            titleRes = Strings.menu_split_editor_horizontal,
            category = MainActivityCommandCategory.VIEW,
            keywords = listOf("split", "horizontal"),
            execute = { callbacks.onSetSplitEditorLayout(SplitEditorLayout.HORIZONTAL) }
        )
        addBuiltInCommand(
            id = "view.split.vertical",
            titleRes = Strings.menu_split_editor_vertical,
            category = MainActivityCommandCategory.VIEW,
            keywords = listOf("split", "vertical"),
            execute = { callbacks.onSetSplitEditorLayout(SplitEditorLayout.VERTICAL) }
        )
    }
    addBuiltInCommand(
        id = "view.split.moveTab",
        titleRes = if (availability.splitEditorLayout == SplitEditorLayout.VERTICAL) {
            Strings.menu_move_tab_to_lower_pane
        } else {
            Strings.menu_move_tab_to_secondary_pane
        },
        category = MainActivityCommandCategory.VIEW,
        enabled = availability.canMoveTabToSecondaryPane,
        keywords = listOf("split", "move tab"),
        execute = callbacks.onMoveTabToSecondaryPane
    )
    addBuiltInCommand(
        id = "view.split.copyTab",
        titleRes = if (availability.splitEditorLayout == SplitEditorLayout.VERTICAL) {
            Strings.menu_copy_tab_to_lower_pane
        } else {
            Strings.menu_copy_tab_to_secondary_pane
        },
        category = MainActivityCommandCategory.VIEW,
        enabled = availability.canCopyTabToSecondaryPane,
        keywords = listOf("split", "copy tab"),
        execute = callbacks.onCopyTabToSecondaryPane
    )
    addHostCommand(
        id = HostCommands.VIEW_TOGGLE_FILE_TREE,
        availability = availability,
        execute = callbacks.onOpenExplorer
    )
    addBuiltInCommand(
        id = COMMAND_GLOBAL_SEARCH,
        titleRes = Strings.menu_global_search,
        category = MainActivityCommandCategory.VIEW,
        keywords = listOf("global search", "find in files"),
        execute = callbacks.onOpenGlobalSearch
    )
    addHostCommand(
        id = HostCommands.VIEW_COMMAND_PALETTE,
        availability = availability,
        execute = callbacks.onOpenCommandPalette
    )
    addHostCommand(
        id = HostCommands.EDITOR_TOGGLE_BOOKMARK,
        availability = availability,
        execute = callbacks.onToggleBookmark
    )
    addHostCommand(
        id = HostCommands.EDITOR_PREVIOUS_BOOKMARK,
        availability = availability,
        execute = callbacks.onPrevBookmark
    )
    addHostCommand(
        id = HostCommands.EDITOR_NEXT_BOOKMARK,
        availability = availability,
        execute = callbacks.onNextBookmark
    )
    addHostCommand(
        id = HostCommands.VIEW_BOOKMARKS,
        availability = availability,
        execute = callbacks.onOpenBookmarks
    )
    addHostCommand(
        id = HostCommands.VIEW_TOGGLE_TERMINAL,
        availability = availability,
        execute = callbacks.onOpenTerminal
    )
    addHostCommand(
        id = HostCommands.VIEW_SETTINGS,
        availability = availability,
        execute = callbacks.onOpenSettings
    )
    addHostCommand(
        id = COMMAND_WORKSPACE_EXIT,
        availability = availability,
        execute = callbacks.onExitWorkspace
    )

    addAll(pluginCommands)
}

private fun resolvePluginEditorToolbarCommands(
    pluginManager: PluginManager,
    enabledPlugins: List<InstalledPlugin>,
    activeFile: File?,
    isDirty: Boolean,
    hostCommandExecutor: HostCommandExecutor?,
): List<MainActivityCommand> {
    if (activeFile == null || hostCommandExecutor == null) return emptyList()

    return pluginManager.resolveEditorToolbarCommands(
        installedPlugins = enabledPlugins,
        file = activeFile,
        isDirty = isDirty
    ).map { item ->
        item.toCommand(
            activeFile = activeFile,
            isDirty = isDirty,
            hostCommandExecutor = hostCommandExecutor
        )
    }
}

private fun ResolvedPluginCommand.toCommand(
    activeFile: File,
    isDirty: Boolean,
    hostCommandExecutor: HostCommandExecutor,
): MainActivityCommand {
    val commandId = commandId.trim()
    val availability = when (source) {
        ResolvedPluginCommandSource.HOST -> null
        ResolvedPluginCommandSource.PLUGIN -> PluginCommandRegistry.availability(commandId, pluginId)
    }
    val disabledReason = availability
        ?.errorMessage
        ?.takeIf(String::isNotBlank)
        ?.let(MainActivityCommandText::Literal)
    return MainActivityCommand(
        id = buildPluginToolbarCommandId(
            pluginId = pluginId,
            group = group,
            commandId = commandId
        ),
        title = MainActivityCommandText.Literal(title),
        category = MainActivityCommandCategory.PLUGIN,
        enabled = availability?.available ?: true,
        disabledReason = disabledReason,
        keywords = listOf("plugin", pluginId, pluginName, commandId),
        source = MainActivityCommandSource.PLUGIN,
        sourceName = pluginName,
        execute = {
            hostCommandExecutor.execute(
                commandId,
                HostCommandInvocation(
                    file = activeFile,
                    isDirectory = activeFile.isDirectory,
                    isDirty = isDirty
                )
            )
        }
    )
}

private fun MutableList<MainActivityCommand>.addBuiltInCommand(
    id: String,
    @StringRes titleRes: Int,
    category: MainActivityCommandCategory,
    enabled: Boolean = true,
    shortcutAction: ShortcutAction? = null,
    keywords: List<String> = emptyList(),
    execute: () -> Unit,
) {
    add(
        MainActivityCommand(
            id = id,
            title = MainActivityCommandText.Resource(titleRes),
            category = category,
            enabled = enabled,
            shortcutAction = shortcutAction,
            keywords = keywords,
            execute = execute
        )
    )
}

private fun MutableList<MainActivityCommand>.addHostCommand(
    id: String,
    availability: MainActivityCommandAvailability,
    enabled: Boolean? = null,
    execute: () -> Unit,
) {
    val descriptor = HostCommandCatalog.requireDescriptor(id)
    add(
        MainActivityCommand(
            id = descriptor.id,
            title = MainActivityCommandText.Resource(descriptor.titleRes),
            category = descriptor.category.toMainActivityCommandCategory(),
            enabled = enabled ?: availability.isEnabled(descriptor),
            shortcutAction = ShortcutAction.forCommandId(descriptor.id),
            keywords = descriptor.keywords,
            execute = execute
        )
    )
}

private fun MainActivityCommandAvailability.isEnabled(descriptor: HostCommandDescriptor): Boolean = when (descriptor.availability) {
    HostCommandAvailability.ALWAYS -> true
    HostCommandAvailability.ACTIVE_FILE -> hasActiveFile
    HostCommandAvailability.DIRTY_ACTIVE_FILE -> hasActiveFile && isDirty
    HostCommandAvailability.ACTIVE_EDITOR -> hasActiveFile
    HostCommandAvailability.BASIC_LSP_NAVIGATION -> isBasicLspNavigationAvailable
    HostCommandAvailability.ADVANCED_LSP_NAVIGATION -> isAdvancedLspNavigationAvailable
    HostCommandAvailability.LSP_REFACTOR -> isLspRefactorAvailable
    HostCommandAvailability.HEADER_SOURCE_SWITCH -> isHeaderSourceSwitchAvailable
    HostCommandAvailability.NAVIGATE_BACK -> canNavigateBack
    HostCommandAvailability.NAVIGATE_FORWARD -> canNavigateForward
    HostCommandAvailability.NOT_COMPILING -> !isCompiling
}
