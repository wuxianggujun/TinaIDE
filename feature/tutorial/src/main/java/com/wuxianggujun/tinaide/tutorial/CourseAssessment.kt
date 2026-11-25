package com.wuxianggujun.tinaide.tutorial

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CourseCheck(val input: String, val expectedOutput: String, val exitCode: Int = 0)
data class CourseCheckOutput(val exitCode: Int, val output: String, val timedOut: Boolean)
data class CourseAssessmentResult(val checks: List<Pair<CourseCheck, CourseCheckOutput>>, val sourceChanged: Boolean) {
    val passed: Boolean get() = !sourceChanged && checks.isNotEmpty() && checks.all { (check, actual) ->
        !actual.timedOut && actual.exitCode == check.exitCode &&
            actual.output.lineSequence().map { it.trim() }.any { it == check.expectedOutput }
    }
}

/** Checks observable behavior only. Visual interactions and knowledge questions remain explicit. */
object CourseAssessment {
    fun checks(stepId: String): List<CourseCheck> = when (stepId) {
        "ide_workflow" -> listOf(CourseCheck("", "Hello, TinaIDE!"), CourseCheck("", "First change"))
        "cpp_basics" -> listOf(
            CourseCheck("9\n", "total=90"), CourseCheck("10\n", "total=90"), CourseCheck("11\n", "total=100"),
            CourseCheck("abc\n", "expected integer", 1), CourseCheck("-1\n", "invalid count", 1),
        )
        "cpp_functions" -> listOf(CourseCheck("", "total=90"))
        "cpp_collections" -> listOf(CourseCheck("10\n", "Tina: items=3 total=30"), CourseCheck("abc\n", "expected integer", 1))
        "cpp_raii", "cmake_practice", "vcpkg_guide", "ai_practice" -> listOf(CourseCheck("", "total=30"))
        else -> emptyList()
    }

    suspend fun assess(
        project: File,
        stepId: String,
        build: suspend () -> Unit,
        run: suspend (String) -> CourseCheckOutput,
    ): CourseAssessmentResult {
        val checks = checks(stepId)
        require(checks.isNotEmpty())
        val before = fingerprint(project)
        build()
        val outputs = checks.map { it to run(it.input) }
        return CourseAssessmentResult(outputs, before != fingerprint(project))
    }

    /** Bounded scan of project sources/configuration, never caches, installed packages or symlinks. */
    private suspend fun fingerprint(project: File): String = withContext(Dispatchers.IO) {
        val root = project.canonicalFile
        require(root.isDirectory && File(root, "CMakeLists.txt").isFile && File(root, "main.cpp").isFile)
        val ignored = setOf("build", "out", ".git", ".tina", ".tinaide", ".cache", "vcpkg_installed", "node_modules")
        val files = mutableListOf<File>()
        var bytes = 0L
        var visited = 0
        fun collect(directory: File, depth: Int) {
            require(depth < 16)
            directory.listFiles()?.forEach { file ->
                require(++visited <= 4_000)
                if (file.name in ignored || file.name == "compile_commands.json") return@forEach
                require(!Files.isSymbolicLink(file.toPath())) { "Linked course sources are not supported" }
                if (file.isDirectory) {
                    if (file.name !in ignored && !file.name.startsWith('.')) collect(file, depth + 1)
                } else if (file.extension.lowercase() in setOf("c", "cpp", "cc", "h", "hpp", "cmake", "json") || file.name == "CMakeLists.txt") {
                    bytes += file.length()
                    require(bytes <= 2_000_000 && files.size < 256)
                    files += file
                }
            } ?: error("Cannot read course directory")
        }
        collect(root, 0)
        val digest = MessageDigest.getInstance("SHA-256")
        files.sortedBy { it.relativeTo(root).path }.forEach { file ->
            digest.update(file.relativeTo(root).path.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            file.inputStream().use { stream ->
                val buffer = ByteArray(8192)
                var readBytes = 0
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    readBytes += count
                    require(readBytes <= 2_000_000)
                    digest.update(buffer, 0, count)
                }
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
