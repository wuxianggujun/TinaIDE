package com.wuxianggujun.tinaide.ui.compose.screens.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import com.wuxianggujun.tinaide.core.lsp.Diagnostic
import com.wuxianggujun.tinaide.core.symbol.IProjectSymbolIndexService
import com.wuxianggujun.tinaide.editor.IEditorManager
import com.wuxianggujun.tinaide.editor.symbol.ProjectSymbolIndexService
import com.wuxianggujun.tinaide.editor.theme.PluginEditorThemeRegistry
import com.wuxianggujun.tinaide.plugin.PluginSnippetManager
import com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerState
import com.wuxianggujun.tinaide.ui.compose.state.editor.rememberEditorContainerState
import org.koin.compose.koinInject

@Stable
internal data class MainActivityEditorHostState(
    val editorContainerState: EditorContainerState,
    val projectSymbolIndexService: ProjectSymbolIndexService?,
)

@Composable
internal fun rememberMainActivityEditorHostState(
    editorManager: IEditorManager,
    projectRootPathProvider: () -> String?,
    onLspDiagnosticsChanged: (String, List<Diagnostic>) -> Unit,
): MainActivityEditorHostState {
    val projectSymbolIndexService = koinInject<IProjectSymbolIndexService>() as? ProjectSymbolIndexService
    val projectSymbolIndexServiceProvider = remember(projectSymbolIndexService) {
        { projectSymbolIndexService }
    }
    val pluginSnippetManager: PluginSnippetManager = koinInject()
    val pluginEditorThemeRegistry: PluginEditorThemeRegistry = koinInject()
    val editorContainerState = rememberEditorContainerState(
        editorManager = editorManager,
        snippetManager = pluginSnippetManager,
        pluginThemeRegistry = pluginEditorThemeRegistry,
        projectSymbolIndexServiceProvider = projectSymbolIndexServiceProvider,
        projectRootPathProvider = projectRootPathProvider,
        onLspDiagnosticsChanged = onLspDiagnosticsChanged,
    )

    return remember(editorContainerState, projectSymbolIndexService) {
        MainActivityEditorHostState(
            editorContainerState = editorContainerState,
            projectSymbolIndexService = projectSymbolIndexService,
        )
    }
}
