package com.wuxianggujun.tinaide.project

import java.io.File
import timber.log.Timber

/**
 * 项目创建服务
 *
 * 统一封装项目目录创建、模板安装和失败清理逻辑，
 * 供项目列表、新建向导等入口复用。
 */
object ProjectCreationService {
    private const val TAG = "ProjectCreation"
    private val SAFE_PROJECT_NAME_REGEX = Regex("^[A-Za-z0-9_-]+$")

    fun createProject(
        projectRoot: File,
        projectName: String,
        templateSpec: ProjectTemplateSpec.Zip,
        cppStandard: CppStandard = CppStandard.DEFAULT,
        ndkApiLevel: AndroidApiLevel? = null,
        authorName: String = ""
    ): ProjectCreationResult {
        val normalizedName = projectName.trim()
        if (normalizedName.isBlank()) {
            return ProjectCreationResult.Failure(ProjectCreationFailure.EMPTY_NAME)
        }

        if (!isValidProjectName(normalizedName)) {
            return ProjectCreationResult.Failure(
                reason = ProjectCreationFailure.INVALID_NAME,
                detail = normalizedName
            )
        }

        if (projectRoot.path.isBlank()) {
            return ProjectCreationResult.Failure(
                reason = ProjectCreationFailure.PROJECT_ROOT_UNAVAILABLE,
                detail = projectRoot.path
            )
        }

        if (!projectRoot.exists() && !projectRoot.mkdirs()) {
            return ProjectCreationResult.Failure(
                reason = ProjectCreationFailure.PROJECT_ROOT_UNAVAILABLE,
                detail = projectRoot.absolutePath
            )
        }

        if (!projectRoot.isDirectory) {
            return ProjectCreationResult.Failure(
                reason = ProjectCreationFailure.PROJECT_ROOT_UNAVAILABLE,
                detail = projectRoot.absolutePath
            )
        }

        val safeRoot = runCatching { projectRoot.canonicalFile }.getOrElse {
            return ProjectCreationResult.Failure(
                reason = ProjectCreationFailure.PROJECT_ROOT_UNAVAILABLE,
                detail = projectRoot.absolutePath
            )
        }
        val projectDir = runCatching { File(safeRoot, normalizedName).canonicalFile }.getOrElse {
            return ProjectCreationResult.Failure(
                reason = ProjectCreationFailure.INVALID_NAME,
                detail = normalizedName
            )
        }
        if (!projectDir.isDirectChildOf(safeRoot)) {
            return ProjectCreationResult.Failure(
                reason = ProjectCreationFailure.INVALID_NAME,
                detail = normalizedName
            )
        }

        if (projectDir.exists()) {
            return ProjectCreationResult.Failure(
                reason = ProjectCreationFailure.ALREADY_EXISTS,
                detail = projectDir.absolutePath
            )
        }

        if (!projectDir.mkdirs()) {
            return ProjectCreationResult.Failure(
                reason = ProjectCreationFailure.CREATE_DIRECTORY_FAILED,
                detail = projectDir.absolutePath
            )
        }

        val templateInstalled = runCatching {
            ProjectTemplateInstaller.install(
                destDir = projectDir,
                projectName = normalizedName,
                templateSpec = templateSpec,
                cppStandard = cppStandard,
                ndkApiLevel = ndkApiLevel,
                authorName = authorName,
            )
        }.onFailure { throwable ->
            Timber.tag(TAG).e(throwable, "Create project failed: %s", normalizedName)
        }.getOrDefault(false)

        if (!templateInstalled) {
            cleanupFailedProject(projectDir, safeRoot)
            return ProjectCreationResult.Failure(
                reason = ProjectCreationFailure.TEMPLATE_INSTALL_FAILED,
                detail = projectDir.absolutePath
            )
        }

        return ProjectCreationResult.Success(projectDir)
    }

    fun isValidProjectName(projectName: String): Boolean {
        if (File(projectName).isAbsolute) return false
        return SAFE_PROJECT_NAME_REGEX.matches(projectName)
    }

    private fun File.isDirectChildOf(parentDir: File): Boolean {
        val parentPath = parentDir.canonicalFile.toPath()
        val childPath = canonicalFile.toPath()
        return childPath.parent == parentPath
    }

    private fun cleanupFailedProject(projectDir: File, safeRoot: File) {
        runCatching {
            if (projectDir.isDirectChildOf(safeRoot) && projectDir.exists()) {
                projectDir.deleteRecursively()
            }
        }.onFailure { throwable ->
            Timber.tag(TAG).w(throwable, "Failed to cleanup project dir: %s", projectDir.absolutePath)
        }
    }
}

sealed interface ProjectCreationResult {
    data class Success(val projectDir: File) : ProjectCreationResult
    data class Failure(
        val reason: ProjectCreationFailure,
        val detail: String? = null
    ) : ProjectCreationResult
}

enum class ProjectCreationFailure {
    EMPTY_NAME,
    INVALID_NAME,
    PROJECT_ROOT_UNAVAILABLE,
    ALREADY_EXISTS,
    CREATE_DIRECTORY_FAILED,
    TEMPLATE_INSTALL_FAILED
}
