package com.wuxianggujun.tinaide.ui.compose.screens.packages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.project.ProjectListItem
import com.wuxianggujun.tinaide.project.VcpkgProjectSettings
import com.wuxianggujun.tinaide.ui.compose.components.TinaCard
import com.wuxianggujun.tinaide.ui.compose.components.TinaOutlinedButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaPrimaryButton
import com.wuxianggujun.tinaide.ui.compose.components.TinaSearchField
import com.wuxianggujun.tinaide.ui.compose.components.TinaSingleChoiceDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaTextButton
import org.koin.androidx.compose.koinViewModel

@Composable
fun VcpkgMarketContent(projects: List<ProjectListItem>, onConfigureProject: (String) -> Unit) {
    val viewModel: VcpkgMarketViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var chooseProject by rememberSaveable { mutableStateOf(false) }
    var pendingDependency by rememberSaveable { mutableStateOf<String?>(null) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, viewModel) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh() }
        owner.lifecycle.addObserver(observer)
        viewModel.refresh()
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val catalog = state.catalog
    val ports = remember(state.available, catalog, state.query) {
        (state.available?.ports.orEmpty() + catalog?.ports.orEmpty()).distinctBy { it.name }.filter {
            state.query.isBlank() || it.name.contains(state.query, true) || it.description.contains(state.query, true)
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(stringResource(Strings.vcpkg_market_description), style = MaterialTheme.typography.bodyMedium)
            TinaTextButton(text = stringResource(Strings.vcpkg_update_catalog), onClick = viewModel::updateCatalog,
                enabled = !state.busy)
            state.available?.let { Text(stringResource(Strings.vcpkg_catalog_count, it.ports.size), style = MaterialTheme.typography.bodySmall) }
            TinaOutlinedButton(
                text = projects.firstOrNull { it.dir.absolutePath == state.projectPath }?.displayName
                    ?: stringResource(Strings.vcpkg_select_project),
                onClick = { chooseProject = true },
                enabled = !state.busy && projects.isNotEmpty(),
            )
            if (projects.isEmpty()) Text(stringResource(Strings.vcpkg_no_projects), style = MaterialTheme.typography.bodySmall)
            if (state.updating) {
                state.downloadProgress?.let { progress -> LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth()) }
                    ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                TinaTextButton(text = stringResource(Strings.btn_cancel), onClick = viewModel::cancelInstall)
            }
        }
        if (state.projectPath.isNotBlank()) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TinaTextButton(text = stringResource(Strings.vcpkg_configure),
                        enabled = !state.busy,
                        onClick = { onConfigureProject(state.projectPath) })
                    TinaTextButton(text = stringResource(Strings.vcpkg_refresh), onClick = viewModel::refresh,
                        enabled = !state.busy)
                }
                Text(stringResource(Strings.vcpkg_managed_paths), style = MaterialTheme.typography.bodySmall)
                if (state.installing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    TinaOutlinedButton(text = stringResource(Strings.btn_cancel_install), onClick = viewModel::cancelInstall)
                } else if (catalog?.settings?.mode == VcpkgProjectSettings.MANIFEST) {
                    TinaPrimaryButton(text = stringResource(Strings.vcpkg_install_project), onClick = viewModel::install,
                        enabled = !state.busy && (catalog.declared.isNotEmpty() || catalog.installed.isNotEmpty()))
                }
                Text(stringResource(Strings.vcpkg_declared_summary, catalog?.declared.orEmpty().sorted().joinToString(", ")))
            }
        }
        state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
        if (catalog != null && catalog.skippedPorts > 0) {
            item { Text(stringResource(Strings.vcpkg_skipped_ports, catalog.skippedPorts)) }
        }
        if (state.output.isNotEmpty()) {
            item { Text(state.output.takeLast(12).joinToString("\n"), style = MaterialTheme.typography.bodySmall) }
        }
        if (state.loading) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        item {
            TinaSearchField(value = state.query, onValueChange = viewModel::search, placeholder = stringResource(Strings.action_search))
        }
        if (!state.loading && ports.isEmpty()) item { Text(stringResource(Strings.vcpkg_empty_catalog)) }
        items(ports, key = { it.name }) { port ->
            TinaCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${port.name} ${port.version}", style = MaterialTheme.typography.titleMedium)
                    Text(port.description, style = MaterialTheme.typography.bodySmall)
                    if (port.supports.isNotBlank()) Text(stringResource(Strings.vcpkg_port_supports, port.supports), style = MaterialTheme.typography.bodySmall)
                    catalog?.installed?.get(port.name)?.let { version ->
                        Text(stringResource(Strings.vcpkg_installed_version, version), color = MaterialTheme.colorScheme.primary)
                    }
                    val declared = port.name in catalog?.declared.orEmpty()
                    TinaTextButton(
                        text = stringResource(if (declared) Strings.vcpkg_remove_dependency else Strings.vcpkg_add_dependency),
                        onClick = {
                            if (state.projectPath.isBlank()) {
                                pendingDependency = port.name
                                chooseProject = true
                            } else viewModel.toggleDependency(port.name, !declared)
                        },
                        enabled = !state.busy && projects.isNotEmpty() && catalog?.settings?.mode != VcpkgProjectSettings.PREBUILT,
                    )
                }
            }
        }
    }
    if (chooseProject) {
        TinaSingleChoiceDialog(
            title = stringResource(Strings.vcpkg_select_project),
            options = projects.map { it.dir.absolutePath to it.displayName },
            selectedValue = state.projectPath,
            onSelected = { path -> viewModel.selectProject(path, pendingDependency); pendingDependency = null; chooseProject = false },
            onDismiss = { pendingDependency = null; chooseProject = false },
        )
    }
}
