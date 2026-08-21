package com.wuxianggujun.tinaide.storage

import android.content.Context
import androidx.room.withTransaction
import com.wuxianggujun.tinaide.core.ServiceLifecycle
import com.wuxianggujun.tinaide.project.ProjectIdentity
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import com.wuxianggujun.tinaide.storage.db.ProjectLocationEntity
import com.wuxianggujun.tinaide.storage.db.StorageDatabase
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 项目位置管理器
 *
 * 职责：
 * - 管理项目的源码路径与项目构建目录
 * - 持久化项目路径配置（使用 Room 数据库）
 */
class ProjectLocationManager(
    private val context: Context,
    private val scope: CoroutineScope
) : ServiceLifecycle {

    companion object {
        private const val TAG = "ProjectLocationManager"
        private const val LEGACY_PRIVATE_PROJECTS_MIGRATION_MARKER =
            "storage-migrations/private-projects-v1.done"
    }

    private val database = StorageDatabase.getInstance(context)
    private val locationDao = database.projectLocationDao()

    // 项目路径映射缓存
    // 读：ConcurrentHashMap 提供无锁安全读 / 安全迭代（getAllProjects 等可在任意线程调用）。
    // 写：所有“复合读改写”（registerProject / unregisterProject / 加载合并）都走 cacheLock，
    //     保证两个 map 之间以及 read-modify-write 的整体原子性。
    //     init 协程的注册与外部 openProject 可能并发，仅靠 ConcurrentHashMap 不足以保证一致。
    private val cacheLock = Any()
    private val persistenceMutex = Mutex()
    private val projectMappingsById = ConcurrentHashMap<String, ProjectLocation>()
    private val projectIdBySourceRootPath = ConcurrentHashMap<String, String>()

    override fun onCreate() {
        Timber.tag(TAG).d("ProjectLocationManager initialized")
        // 不在装配线程（可能是主线程）同步读数据库。
        // 整个初始化序列放进 IO 协程，内部保持原有串行顺序：
        // 先加载映射，再迁移遗留项目、注册私有项目（后两者依赖映射已就绪）。
        scope.launch(Dispatchers.IO) {
            loadProjectMappings()
            migrateLegacyPrivateProjectsIfNeeded()
            registerProjectsFromPrivateRoot()
        }
    }

    override fun onDestroy() {
        Timber.tag(TAG).d("ProjectLocationManager destroyed")
        // 缓存已经实时同步到数据库，无需额外保存
    }

    fun getProjectLocation(projectId: String): ProjectLocation? = projectMappingsById[projectId]

    fun registerProject(sourceDir: File): ProjectLocation {
        val registration = prepareProjectRegistration(sourceDir)
        if (registration.changed) {
            saveProjectMapping(registration.location)
            Timber.tag(TAG).i("Registered project mapping")
        }
        return registration.location
    }

    suspend fun registerProjectAndAwait(sourceDir: File): ProjectLocation = withContext(Dispatchers.IO) {
        val registration = prepareProjectRegistration(sourceDir)
        try {
            persistProjectMapping(registration.location)
            if (registration.changed) {
                Timber.tag(TAG).i("Registered project mapping")
            }
        } catch (error: Throwable) {
            if (registration.changed) {
                rollbackPreparedRegistration(registration)
            }
            throw error
        }
        registration.location
    }

    private fun prepareProjectRegistration(sourceDir: File): PreparedProjectRegistration {
        require(sourceDir.exists() && sourceDir.isDirectory) {
            "Invalid project source directory"
        }

        val normalizedSourceDir = normalizePath(sourceDir)
        val metadata = ProjectMetadataStore.ensure(sourceDir, displayNameFallback = sourceDir.name)
        val projectId = ProjectIdentity.requireValid(metadata.id)
        val projectDirName = sourceDir.name

        // 锁内完成“读 existing → 计算 location → 改两个 map”的复合操作，保证原子性。
        // 文件 IO（ensure）与异步落库（saveProjectMapping）都放在锁外，避免锁内做慢操作。
        return synchronized(cacheLock) {
            val previous = projectMappingsById[projectId]
            check(
                previous == null ||
                    previous.sourceRootPath == normalizedSourceDir ||
                    !File(previous.sourceRootPath).isDirectory
            ) { "Project identity is already registered at another location" }
            val displacedProjectId = projectIdBySourceRootPath[normalizedSourceDir]
                ?.takeUnless { it == projectId }
            val displacedLocation = displacedProjectId?.let(projectMappingsById::get)
            val resolved = when {
                previous == null -> ProjectLocation(
                    projectId = projectId,
                    projectDirName = projectDirName,
                    sourceRootPath = normalizedSourceDir,
                    registered = System.currentTimeMillis()
                )
                previous.projectDirName != projectDirName || previous.sourceRootPath != normalizedSourceDir ->
                    previous.copy(
                        projectDirName = projectDirName,
                        sourceRootPath = normalizedSourceDir
                    )
                else -> previous
            }

            if (previous != null && previous.sourceRootPath != normalizedSourceDir) {
                projectIdBySourceRootPath.remove(previous.sourceRootPath)
            }
            if (displacedProjectId != null) {
                projectMappingsById.remove(displacedProjectId)
            }
            projectMappingsById[projectId] = resolved
            projectIdBySourceRootPath[normalizedSourceDir] = projectId
            PreparedProjectRegistration(
                location = resolved,
                previousLocation = previous,
                displacedLocation = displacedLocation,
                changed = previous != resolved || displacedProjectId != null,
            )
        }
    }

    fun getAllProjects(): List<ProjectLocation> = projectMappingsById.values.toList()

    fun getSourceDir(projectId: String): File? = projectMappingsById[projectId]?.let { File(it.sourceRootPath) }

    fun getWorkspaceDir(projectId: String): File {
        require(projectMappingsById.containsKey(projectId)) {
            "Project not registered: $projectId"
        }
        return ProjectPaths.getProjectWorkspaceDir(context, projectId).apply { mkdirs() }
    }

    fun getBuildDir(projectId: String): File = ProjectPaths.getProjectBuildDir(getWorkspaceDir(projectId)).apply { mkdirs() }

    fun unregisterProject(projectId: String, deleteWorkspace: Boolean = false): Boolean {
        // 锁内移除两个 map 的对应条目，与 registerProject 互斥。
        val location = synchronized(cacheLock) {
            val removed = projectMappingsById.remove(projectId) ?: return false
            projectIdBySourceRootPath.remove(removed.sourceRootPath)
            removed
        }

        if (deleteWorkspace) {
            val workspaceDir = ProjectPaths.getProjectWorkspaceDir(context, projectId)
            if (workspaceDir.exists()) {
                workspaceDir.deleteRecursively()
                Timber.tag(TAG).i("Deleted project workspace directory")
            }
        }

        deleteProjectMapping(projectId)
        Timber.tag(TAG).i("Unregistered project mapping")
        return true
    }

    suspend fun unregisterProjectAndAwait(projectId: String, deleteWorkspace: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            if (!unregisterProject(projectId, deleteWorkspace)) return@withContext false
            persistenceMutex.withLock {
                val isStillUnregistered = synchronized(cacheLock) {
                    !projectMappingsById.containsKey(projectId)
                }
                if (isStillUnregistered) {
                    locationDao.deleteLocation(projectId)
                }
            }
            true
        }

    private suspend fun loadProjectMappings() = withContext(Dispatchers.IO) {
        try {
            val entities = locationDao.getAllLocations()

            // 先在锁外完成每条记录的规整与文件 IO（修正遗留 sourceRootPath、ensure 元数据）。
            val loaded = entities.mapNotNull { entity ->
                runCatching {
                    var location = entity.toDomainModel()
                    var needsPersistence = false
                    if (location.sourceRootPath.isBlank() || isLegacyPendingSourceRoot(location.sourceRootPath)) {
                        val fallbackDir = ProjectPaths.getPrivateProjectDir(context, location.projectDirName)
                        location = location.copy(sourceRootPath = normalizePath(fallbackDir))
                        needsPersistence = true
                    } else {
                        val normalizedSourceRootPath = normalizePath(location.sourceRootPath)
                        if (normalizedSourceRootPath != location.sourceRootPath) {
                            location = location.copy(sourceRootPath = normalizedSourceRootPath)
                            needsPersistence = true
                        }
                    }

                    val sourceDir = File(location.sourceRootPath)
                    if (sourceDir.exists() && sourceDir.isDirectory) {
                        val metadata = ProjectMetadataStore.ensure(
                            sourceDir,
                            displayNameFallback = location.projectDirName,
                        )
                        if (metadata.id != location.projectId) {
                            location = location.copy(projectId = metadata.id)
                            needsPersistence = true
                        }
                    }
                    ProjectIdentity.requireValid(location.projectId)
                    location to needsPersistence
                }.onFailure { error ->
                    Timber.tag(TAG).w(
                        "Skipped invalid persisted project mapping: error=%s",
                        error.javaClass.simpleName,
                    )
                }.getOrNull()
            }

            // 锁内合并：不再 clear。加载是在 IO 协程里异步进行的，期间外部可能已通过
            // openProject/restoreLastSession 注册了项目，那些条目比数据库快照更新鲜。
            // 因此用 putIfAbsent 语义：仅补齐数据库里有、而缓存中尚无的项目，已存在则跳过。
            val migratedMappings = synchronized(cacheLock) {
                loaded.mapNotNull { (location, needsPersistence) ->
                    val alreadyRegistered = projectMappingsById.containsKey(location.projectId) ||
                        projectIdBySourceRootPath.containsKey(location.sourceRootPath)
                    if (!alreadyRegistered) {
                        projectMappingsById[location.projectId] = location
                        projectIdBySourceRootPath[location.sourceRootPath] = location.projectId
                        location.takeIf { needsPersistence }
                    } else {
                        null
                    }
                }
            }
            migratedMappings.forEach(::saveProjectMapping)

            Timber.tag(TAG).i("Loaded %d project mappings from database", projectMappingsById.size)
        } catch (e: Exception) {
            Timber.tag(TAG).e("Failed to load project mappings: %s", e.javaClass.simpleName)
        }
    }

    private fun saveProjectMapping(location: ProjectLocation) {
        scope.launch(Dispatchers.IO) {
            try {
                persistProjectMapping(location)
                Timber.tag(TAG).d("Saved project mapping")
            } catch (e: Exception) {
                Timber.tag(TAG).e("Failed to save project mapping: %s", e.javaClass.simpleName)
            }
        }
    }

    private suspend fun persistProjectMapping(location: ProjectLocation) {
        persistenceMutex.withLock {
            val isCurrent = synchronized(cacheLock) {
                projectMappingsById[location.projectId] == location &&
                    projectIdBySourceRootPath[location.sourceRootPath] == location.projectId
            }
            check(isCurrent) { "Project mapping changed before it could be persisted" }
            database.withTransaction {
                locationDao.deleteOtherLocationsForSourcePath(
                    sourceRootPath = location.sourceRootPath,
                    projectId = location.projectId,
                )
                locationDao.insertLocation(ProjectLocationEntity.fromDomainModel(location))
            }
        }
    }

    private fun rollbackPreparedRegistration(registration: PreparedProjectRegistration) {
        synchronized(cacheLock) {
            if (projectMappingsById[registration.location.projectId] != registration.location ||
                projectIdBySourceRootPath[registration.location.sourceRootPath] != registration.location.projectId
            ) {
                return
            }

            projectMappingsById.remove(registration.location.projectId)
            projectIdBySourceRootPath.remove(registration.location.sourceRootPath)
            registration.previousLocation?.let { previous ->
                projectMappingsById[previous.projectId] = previous
                projectIdBySourceRootPath[previous.sourceRootPath] = previous.projectId
            }
            registration.displacedLocation?.let { displaced ->
                projectMappingsById[displaced.projectId] = displaced
                projectIdBySourceRootPath[displaced.sourceRootPath] = displaced.projectId
            }
        }
    }

    private fun deleteProjectMapping(projectId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                persistenceMutex.withLock {
                    val isStillUnregistered = synchronized(cacheLock) {
                        !projectMappingsById.containsKey(projectId)
                    }
                    if (!isStillUnregistered) {
                        Timber.tag(TAG).d("Skipped stale project mapping deletion")
                        return@withLock
                    }
                    locationDao.deleteLocation(projectId)
                    Timber.tag(TAG).d("Deleted project mapping")
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e("Failed to delete project mapping: %s", e.javaClass.simpleName)
            }
        }
    }

    fun findProjectByPath(projectPath: String): ProjectLocation? = projectIdBySourceRootPath[normalizePath(projectPath)]
        ?.let(projectMappingsById::get)

    private fun migrateLegacyPrivateProjectsIfNeeded() {
        val markerFile = File(context.filesDir, LEGACY_PRIVATE_PROJECTS_MIGRATION_MARKER)
        if (markerFile.exists()) {
            return
        }

        val legacyRoot = ProjectPaths.getWorkspaceRoot(context)
        val privateProjectsRoot = ProjectPaths.getPrivateProjectsRoot(context).apply { mkdirs() }
        if (!legacyRoot.exists() || !legacyRoot.isDirectory) {
            writeMigrationMarker(markerFile)
            return
        }

        var failed = false
        legacyRoot.listFiles()
            ?.asSequence()
            ?.filter(File::isDirectory)
            ?.forEach { legacyDir ->
                val targetDir = resolveLegacyProjectTarget(privateProjectsRoot, legacyDir)
                val moved = moveLegacyProjectDir(legacyDir, targetDir)
                if (!moved) {
                    failed = true
                    return@forEach
                }

                runCatching { registerProject(targetDir) }
                    .onFailure { error ->
                        failed = true
                        Timber.tag(TAG).e(
                            "Failed to register migrated legacy project: %s",
                            error.javaClass.simpleName
                        )
                    }
            }

        if (!failed) {
            writeMigrationMarker(markerFile)
        }
    }

    private fun registerProjectsFromPrivateRoot() {
        val privateProjectsRoot = ProjectPaths.getPrivateProjectsRoot(context).apply { mkdirs() }
        privateProjectsRoot.listFiles()
            ?.asSequence()
            ?.filter { dir -> dir.isDirectory && !dir.name.startsWith('.') }
            ?.forEach { dir ->
                runCatching { registerProject(dir) }
                    .onFailure { error ->
                        Timber.tag(TAG).e("Failed to register private project: %s", error.javaClass.simpleName)
                    }
            }
    }

    private fun resolveLegacyProjectTarget(privateProjectsRoot: File, legacyDir: File): File {
        val preferredTarget = File(privateProjectsRoot, legacyDir.name)
        if (!preferredTarget.exists()) {
            return preferredTarget
        }

        val legacyProjectId = ProjectMetadataStore.read(legacyDir)?.id
        if (!legacyProjectId.isNullOrBlank() && ProjectMetadataStore.read(preferredTarget)?.id == legacyProjectId) {
            return preferredTarget
        }

        return buildUniqueTargetDir(privateProjectsRoot, legacyDir.name)
    }

    private fun buildUniqueTargetDir(root: File, baseName: String): File {
        var index = 1
        while (true) {
            val candidate = File(root, "$baseName-$index")
            if (!candidate.exists()) {
                return candidate
            }
            index++
        }
    }

    private fun moveLegacyProjectDir(source: File, target: File): Boolean {
        if (source.canonicalOrAbsolutePath() == target.canonicalOrAbsolutePath()) {
            return true
        }

        val sourceProjectId = ProjectMetadataStore.read(source)?.id
        val targetProjectId = target.takeIf(File::exists)?.let(ProjectMetadataStore::read)?.id
        if (!sourceProjectId.isNullOrBlank() && sourceProjectId == targetProjectId) {
            if (!source.deleteRecursively()) {
                Timber.tag(TAG).w("Failed to delete duplicate legacy project directory")
            }
            Timber.tag(TAG).i("Skipped duplicate legacy project directory")
            return true
        }

        target.parentFile?.mkdirs()
        if (source.renameTo(target)) {
            Timber.tag(TAG).i("Migrated legacy private project")
            return true
        }

        return runCatching {
            source.copyRecursively(target, overwrite = false)
            if (!source.deleteRecursively()) {
                Timber.tag(TAG).w("Legacy project source was not fully deleted after copy")
            }
            Timber.tag(TAG).i("Migrated legacy private project by copy")
            true
        }.getOrElse { throwable ->
            target.deleteRecursively()
            Timber.tag(TAG).e(
                "Failed to migrate legacy private project: %s",
                throwable.javaClass.simpleName
            )
            false
        }
    }

    private fun writeMigrationMarker(markerFile: File) {
        runCatching {
            markerFile.parentFile?.mkdirs()
            markerFile.writeText("done", Charsets.UTF_8)
        }.onFailure { error ->
            Timber.tag(TAG).w("Failed to write migration marker: %s", error.javaClass.simpleName)
        }
    }

    private fun isLegacyPendingSourceRoot(path: String): Boolean = path.startsWith(ProjectLocationEntity.LEGACY_PENDING_SOURCE_ROOT_PREFIX)

    private fun normalizePath(path: String): String = runCatching { File(path).canonicalPath }.getOrElse { File(path).absolutePath }

    private fun normalizePath(file: File): String = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }

    private fun File.canonicalOrAbsolutePath(): String = runCatching { canonicalPath }.getOrElse { absolutePath }

    private data class PreparedProjectRegistration(
        val location: ProjectLocation,
        val previousLocation: ProjectLocation?,
        val displacedLocation: ProjectLocation?,
        val changed: Boolean,
    )
}

/**
 * 项目位置信息
 *
 * @property projectId 项目 ID（稳定）
 * @property projectDirName 项目目录名
 * @property registered 注册时间戳
 */
data class ProjectLocation(
    val projectId: String,
    val projectDirName: String,
    val sourceRootPath: String,
    val registered: Long
)
