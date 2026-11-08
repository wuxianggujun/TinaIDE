package com.wuxianggujun.tinaide.ui.compose.screens.main.market

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wuxianggujun.tinaide.settings.SettingsActivity
import com.wuxianggujun.tinaide.ui.ProjectManagerViewModel
import com.wuxianggujun.tinaide.ui.compose.screens.packages.VcpkgMarketContent
import com.wuxianggujun.tinaide.project.ProjectListItem
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel

@Composable
internal fun VcpkgMarketHost() {
    val context = LocalContext.current
    val projectsViewModel: ProjectManagerViewModel = koinViewModel()
    val projects by projectsViewModel.projects.collectAsStateWithLifecycle()
    val cmakeProjects by produceState(initialValue = emptyList<ProjectListItem>(), projects) {
        value = withContext(Dispatchers.IO) { projects.filter { File(it.dir, "CMakeLists.txt").isFile } }
    }
    LaunchedEffect(projectsViewModel) { projectsViewModel.reloadProjects() }
    VcpkgMarketContent(projects = cmakeProjects, onConfigureProject = { SettingsActivity.startForProject(context, it) })
}
