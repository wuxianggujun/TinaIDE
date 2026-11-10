package com.wuxianggujun.tinaide.core.packages

import android.content.Context
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import com.wuxianggujun.tinaide.project.VcpkgProjectSettings
import com.wuxianggujun.tinaide.storage.ProjectPaths
import java.io.File

/** One path policy for the market, CMake, incremental inputs and runtime/export library lookup. */
object VcpkgManagedPaths {
    suspend fun workspace(context: Context, project: File): File =
        ProjectPaths.getProjectWorkspaceDir(context, ProjectMetadataStore.ensure(project).id)

    suspend fun installed(context: Context, project: File, settings: VcpkgProjectSettings): File =
        settings.installationRoot(project, if (settings.mode == VcpkgProjectSettings.MANIFEST)
            ProjectPaths.getProjectVcpkgInstalledDir(context, ProjectMetadataStore.ensure(project).id) else null)
}
