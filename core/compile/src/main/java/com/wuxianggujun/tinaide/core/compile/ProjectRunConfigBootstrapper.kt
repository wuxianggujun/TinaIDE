package com.wuxianggujun.tinaide.core.compile

import com.wuxianggujun.tinaide.project.ProjectApkExportSupportResolver
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import java.io.File
import timber.log.Timber

/**
 * 在项目创建完成后，为需要显式运行配置的项目补齐默认 run config。
 */
object ProjectRunConfigBootstrapper {
    private const val TAG = "ProjectRunConfigBootstrapper"

    fun initializeIfMissing(projectDir: File): Boolean {
        if (!projectDir.isDirectory) return false

        val projectPath = projectDir.absolutePath
        val configFile = RunConfigurationManager.configFile(projectPath)
        if (configFile.exists()) return false

        ProjectApkExportSupportResolver.ensureDetected(projectDir)
        val metadata = ProjectMetadataStore.read(projectDir) ?: return false
        val defaultConfig = CMakeRunTargetResolver.createDefaultRunConfiguration(
            metadata = metadata,
            requireTargetForNonSdl = true,
        ) ?: return false

        val defaultManager = RunConfigurationManager(
            configurations = listOf(defaultConfig),
            selectedId = defaultConfig.id,
        )
        val saved = RunConfigurationManager.save(projectPath, defaultManager)
        if (saved) {
            Timber.tag(TAG).i(
                "Initialized explicit run config for project: %s target=%s outputMode=%s",
                projectPath,
                defaultConfig.targetName,
                defaultConfig.outputMode,
            )
        }
        return saved
    }

}
