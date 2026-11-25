package com.wuxianggujun.tinaide.tutorial.project

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.wuxianggujun.tinaide.project.CppStandard
import com.wuxianggujun.tinaide.project.ProjectBuildSystem
import com.wuxianggujun.tinaide.project.ProjectCreationFailure
import com.wuxianggujun.tinaide.project.ProjectCreationResult
import com.wuxianggujun.tinaide.project.ProjectCreationService
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import com.wuxianggujun.tinaide.project.ProjectTemplateSpec
import com.wuxianggujun.tinaide.storage.ProjectPaths
import com.wuxianggujun.tinaide.tutorial.repository.TutorialRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

fun interface TutorialProjectPreparer {
    suspend fun prepare(stepId: String, language: String): File
}

/** Creates only new directories; reopening a chapter never rewrites its files or dependency settings. */
class TutorialProjectRepository internal constructor(
    private val projectsRoot: File,
    private val cacheRoot: File,
    private val readLesson: (lesson: String, language: String) -> String,
) : TutorialProjectPreparer {
    constructor(context: Context) : this(
        ProjectPaths.getPrivateProjectsRoot(context.applicationContext),
        ProjectPaths.getCacheRoot(context.applicationContext),
        { lesson, language ->
            val prefix = if (language == "en") "tutorial-course/en" else "tutorial-course"
            context.applicationContext.assets.open("$prefix/$lesson.md").use { readUtf8(it, MAX_LESSON_BYTES) }
        },
    )

    override suspend fun prepare(stepId: String, language: String): File = withContext(Dispatchers.IO) {
        require(language == "zh" || language == "en") { "Unsupported course language" }
        require(TutorialProjectBlueprints.supports(stepId)) { "Unknown course project: $stepId" }
        creationMutex.withLock {
            val root = ensureDirectory(projectsRoot).canonicalFile
            findExistingProject(root, stepId)?.let { return@withLock it }
            val blueprint = TutorialProjectBlueprints.load(stepId) { readLesson(it, language) }
            val zip = File.createTempFile("tina-course-", ".zip", ensureDirectory(cacheRoot))
            try {
                ZipOutputStream(zip.outputStream(), Charsets.UTF_8).use { archive ->
                    blueprint.files.forEach { (name, contents) ->
                        archive.putNextEntry(ZipEntry(name))
                        archive.write(contents.toByteArray(Charsets.UTF_8))
                        archive.closeEntry()
                    }
                }
                // Once installation starts, finish its small local transaction even if the reader closes.
                // The ViewModel discards stale navigation; a later click can resume the complete project.
                withContext(NonCancellable) { createProject(root, zip, blueprint) }
            } finally {
                if (zip.exists() && !zip.delete()) Log.w(TAG, "Could not remove course template cache")
            }
        }
    }

    private suspend fun findExistingProject(root: File, stepId: String): File? {
        val children = root.listFiles() ?: throw IOException("Could not list course projects")
        for (child in children.sortedBy { it.name }) {
            if (!child.isDirectory || !isDirectChild(child, root)) continue
            val marker = readMarker(child) ?: continue
            if (marker.schemaVersion != MARKER_SCHEMA || marker.courseId != TutorialRepository.CPP_PRACTICE_COURSE_ID ||
                marker.stepId != stepId
            ) continue
            if (ProjectMetadataStore.read(child)?.id == marker.projectId) return child
        }
        return null
    }

    private suspend fun createProject(root: File, zip: File, blueprint: TutorialProjectBlueprint): File {
        val template = ProjectTemplateSpec.Zip(
            id = "course:${blueprint.stepId}",
            zipFile = zip,
            buildSystem = ProjectBuildSystem.CMAKE,
            isNdkTemplate = blueprint.isSdl3,
            defaultRunTargetName = TutorialProjectBlueprints.RUN_TARGET,
            defaultSdlTargetName = TutorialProjectBlueprints.RUN_TARGET.takeIf { blueprint.isSdl3 },
        )
        for (suffix in 0 until MAX_NAME_ATTEMPTS) {
            val name = blueprint.directoryName + if (suffix == 0) "" else "_${suffix + 1}"
            // Check the lexical name before canonicalization so a conflicting symlink is not followed.
            if (Files.exists(File(root, name).toPath(), LinkOption.NOFOLLOW_LINKS)) continue
            when (val result = ProjectCreationService.createProject(
                projectRoot = root,
                projectName = name,
                templateSpec = template,
                cppStandard = CppStandard.CPP_17,
                useVcpkg = blueprint.useVcpkg,
            )) {
                is ProjectCreationResult.Failure -> {
                    if (result.reason == ProjectCreationFailure.ALREADY_EXISTS) continue
                    throw IOException("Course project creation failed: ${result.reason}")
                }
                is ProjectCreationResult.Success -> {
                    val directory = result.projectDir
                    try {
                        val metadata = checkNotNull(ProjectMetadataStore.read(directory)) { "Missing course project metadata" }
                        writeMarker(directory, CourseProjectMarker(
                            courseId = TutorialRepository.CPP_PRACTICE_COURSE_ID,
                            stepId = blueprint.stepId,
                            projectId = metadata.id,
                        ))
                        return directory
                    } catch (error: Exception) {
                        if (isDirectChild(directory, root) && !directory.deleteRecursively()) {
                            Log.e(TAG, "Could not roll back newly created course project")
                        }
                        throw error
                    }
                }
            }
        }
        throw IOException("No available course project name")
    }

    private fun readMarker(directory: File): CourseProjectMarker? {
        val file = File(directory, MARKER_FILE)
        return try {
            if (!file.isFile || file.canonicalFile != file.absoluteFile.normalize()) return null
            AtomicFile(file).openRead().use { json.decodeFromString<CourseProjectMarker>(readUtf8(it, MAX_MARKER_BYTES)) }
        } catch (error: Exception) {
            Log.w(TAG, "Ignoring invalid course project marker", error)
            null
        }
    }

    private fun writeMarker(directory: File, marker: CourseProjectMarker) {
        val file = AtomicFile(File(directory, MARKER_FILE))
        val output = file.startWrite()
        try {
            output.write(json.encodeToString(CourseProjectMarker.serializer(), marker).toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    private fun isDirectChild(directory: File, root: File): Boolean =
        directory.canonicalFile.parentFile == root && directory.absoluteFile.normalize() == directory.canonicalFile

    private fun ensureDirectory(directory: File): File {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Course directory is unavailable")
        return directory
    }

    @Serializable
    private data class CourseProjectMarker(
        val schemaVersion: Int = MARKER_SCHEMA,
        val courseId: String,
        val stepId: String,
        val projectId: String,
    )

    companion object {
        internal const val MARKER_FILE = ".tina-course.json"
        private const val TAG = "TutorialProject"
        private const val MARKER_SCHEMA = 1
        private const val MAX_NAME_ATTEMPTS = 100
        private const val MAX_MARKER_BYTES = 2048
        private const val MAX_LESSON_BYTES = 128 * 1024
        private val creationMutex = Mutex()
        private val json = Json { encodeDefaults = true }

        private fun readUtf8(input: InputStream, maxBytes: Int): String {
            val bytes = ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= maxBytes) { "Course text exceeds size limit" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        }
    }
}
