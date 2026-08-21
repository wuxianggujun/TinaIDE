package com.wuxianggujun.tinaide.core.lsp

import com.wuxianggujun.tinaide.project.NativeBuildFlagTokenizer
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class CxxCompileContextMode {
    LOCAL,
    REMOTE,
}

enum class CxxCompileDatabaseSource {
    EXTERNAL,
    TINA_FALLBACK,
}

enum class CxxCompileCommandMatch {
    EXACT,
    INFERRED,
    MISSING,
}

enum class CxxCompileContextIssue {
    COMPILE_DATABASE_MISSING,
    COMPILE_DATABASE_INVALID,
    FILE_COMMAND_MISSING,
    COMPILE_SETUP_UNAVAILABLE,
    TOOLCHAIN_SETUP_FAILED,
    CLANGD_START_FAILED,
}

/** A user-facing snapshot of the compile context clangd receives for one C/C++ file. */
data class CxxCompileContextSnapshot(
    val filePath: String,
    val workspaceRootPath: String,
    val mode: CxxCompileContextMode,
    val projectType: CompileDatabaseProvider.ProjectType? = null,
    val compileDatabasePath: String? = null,
    val compileDatabaseSource: CxxCompileDatabaseSource? = null,
    val compileDatabaseUpdatedAtMillis: Long? = null,
    val commandMatch: CxxCompileCommandMatch? = null,
    val matchedSourcePath: String? = null,
    val compilerPath: String? = null,
    val languageStandard: String? = null,
    val targetTriple: String? = null,
    val sysrootPath: String? = null,
    val resourceDirectoryPath: String? = null,
    val includePaths: List<String> = emptyList(),
    val defines: List<String> = emptyList(),
    val commandArguments: List<String> = emptyList(),
    val toolchainId: String? = null,
    val sysrootProfileId: String? = null,
    val sysrootApiLevel: Int? = null,
    val issue: CxxCompileContextIssue? = null,
) {
    companion object {
        fun remote(file: File, workspaceRoot: File): CxxCompileContextSnapshot =
            CxxCompileContextSnapshot(
                filePath = file.stablePath(),
                workspaceRootPath = workspaceRoot.stablePath(),
                mode = CxxCompileContextMode.REMOTE,
            )

        fun unavailable(
            file: File,
            workspaceRoot: File,
            issue: CxxCompileContextIssue,
        ): CxxCompileContextSnapshot = CxxCompileContextSnapshot(
            filePath = file.stablePath(),
            workspaceRootPath = workspaceRoot.stablePath(),
            mode = CxxCompileContextMode.LOCAL,
            issue = issue,
        )
    }
}

/** Reads compile_commands.json through a structured JSON parser and explains the selected command. */
object CxxCompileContextInspector {
    private val json = Json { ignoreUnknownKeys = true }
    private val headerExtensions = setOf("h", "hh", "hpp", "hxx", "inc", "inl")

    fun inspect(
        prepared: CompileDatabaseProvider.Prepared,
        compileCommandsDir: File,
    ): CxxCompileContextSnapshot {
        val databaseFile = File(compileCommandsDir, "compile_commands.json")
        val base = CxxCompileContextSnapshot(
            filePath = prepared.file.stablePath(),
            workspaceRootPath = prepared.workspaceRoot.stablePath(),
            mode = CxxCompileContextMode.LOCAL,
            projectType = prepared.projectType,
            compileDatabasePath = databaseFile.absolutePath,
            compileDatabaseSource = prepared.compileDatabaseSource,
            compileDatabaseUpdatedAtMillis = databaseFile.takeIf(File::isFile)?.lastModified(),
            languageStandard = prepared.desiredCppStandardFlag,
            toolchainId = prepared.toolchainId,
            sysrootProfileId = prepared.sysrootProfileId,
            sysrootApiLevel = prepared.sysrootApiLevel,
        )
        if (!databaseFile.isFile || databaseFile.length() <= 0L) {
            return base.copy(issue = CxxCompileContextIssue.COMPILE_DATABASE_MISSING)
        }

        val entries = runCatching { parseEntries(databaseFile) }
            .getOrElse {
                return base.copy(issue = CxxCompileContextIssue.COMPILE_DATABASE_INVALID)
            }
        val selection = selectEntry(prepared.file, entries)
            ?: return base.copy(
                commandMatch = CxxCompileCommandMatch.MISSING,
                issue = CxxCompileContextIssue.FILE_COMMAND_MISSING,
            )
        val command = explainArguments(selection.entry.arguments, selection.entry.directory)

        return base.copy(
            commandMatch = selection.match,
            matchedSourcePath = selection.entry.resolvedFilePath,
            compilerPath = command.compilerPath,
            languageStandard = command.languageStandard ?: base.languageStandard,
            targetTriple = command.targetTriple,
            sysrootPath = command.sysrootPath,
            resourceDirectoryPath = command.resourceDirectoryPath,
            includePaths = command.includePaths,
            defines = command.defines,
            commandArguments = selection.entry.arguments,
        )
    }

    private data class CompileCommandEntry(
        val directory: File,
        val resolvedFilePath: String,
        val arguments: List<String>,
    )

    private data class SelectedEntry(
        val entry: CompileCommandEntry,
        val match: CxxCompileCommandMatch,
    )

    private data class ExplainedArguments(
        val compilerPath: String?,
        val languageStandard: String?,
        val targetTriple: String?,
        val sysrootPath: String?,
        val resourceDirectoryPath: String?,
        val includePaths: List<String>,
        val defines: List<String>,
    )

    private fun parseEntries(databaseFile: File): List<CompileCommandEntry> {
        val root = json.parseToJsonElement(databaseFile.readText(Charsets.UTF_8)) as? JsonArray
            ?: error("compile_commands root must be an array")
        return root.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val directory = entry.string("directory")
                ?.let(::File)
                ?: databaseFile.parentFile
                ?: return@mapNotNull null
            val file = entry.string("file") ?: return@mapNotNull null
            val arguments = (entry["arguments"] as? JsonArray)
                ?.mapNotNull { value -> (value as? JsonPrimitive)?.contentOrNull }
                ?.takeIf(List<String>::isNotEmpty)
                ?: entry.string("command")
                    ?.let(NativeBuildFlagTokenizer::tokenize)
                    .orEmpty()
            if (arguments.isEmpty()) return@mapNotNull null
            CompileCommandEntry(
                directory = directory,
                resolvedFilePath = resolvePath(file, directory),
                arguments = arguments,
            )
        }
    }

    private fun selectEntry(file: File, entries: List<CompileCommandEntry>): SelectedEntry? {
        val targetPath = file.stablePath()
        entries.firstOrNull { it.resolvedFilePath == targetPath }?.let { exact ->
            return SelectedEntry(exact, CxxCompileCommandMatch.EXACT)
        }
        if (file.extension.lowercase() !in headerExtensions) return null

        val targetStem = file.nameWithoutExtension
        val targetParent = file.parentFile?.stablePath()
        val inferred = entries.firstOrNull { entry ->
            File(entry.resolvedFilePath).nameWithoutExtension == targetStem
        } ?: entries.firstOrNull { entry ->
            File(entry.resolvedFilePath).parentFile?.stablePath() == targetParent
        } ?: entries.firstOrNull()
        return inferred?.let { SelectedEntry(it, CxxCompileCommandMatch.INFERRED) }
    }

    private fun explainArguments(arguments: List<String>, directory: File): ExplainedArguments {
        var languageStandard: String? = null
        var targetTriple: String? = null
        var sysrootPath: String? = null
        var resourceDirectoryPath: String? = null
        val includePaths = linkedSetOf<String>()
        val defines = linkedSetOf<String>()

        var index = 1
        while (index < arguments.size) {
            val argument = arguments[index]
            when {
                argument.startsWith("-std=") -> languageStandard = argument.substringAfter('=')
                argument == "-std" -> arguments.getOrNull(++index)?.let { languageStandard = it }
                argument.startsWith("--target=") -> targetTriple = argument.substringAfter('=')
                argument == "--target" || argument == "-target" ->
                    arguments.getOrNull(++index)?.let { targetTriple = it }
                argument.startsWith("--sysroot=") ->
                    sysrootPath = resolvePath(argument.substringAfter('='), directory)
                argument == "--sysroot" || argument == "-isysroot" ->
                    arguments.getOrNull(++index)?.let { sysrootPath = resolvePath(it, directory) }
                argument.startsWith("-resource-dir=") ->
                    resourceDirectoryPath = resolvePath(argument.substringAfter('='), directory)
                argument == "-resource-dir" ->
                    arguments.getOrNull(++index)?.let { resourceDirectoryPath = resolvePath(it, directory) }
                argument == "-I" || argument == "-isystem" || argument == "-iquote" ->
                    arguments.getOrNull(++index)?.let { includePaths += resolvePath(it, directory) }
                argument.startsWith("-I") && argument.length > 2 ->
                    includePaths += resolvePath(argument.substring(2), directory)
                argument.startsWith("-isystem=") ->
                    includePaths += resolvePath(argument.substringAfter('='), directory)
                argument.startsWith("-iquote=") ->
                    includePaths += resolvePath(argument.substringAfter('='), directory)
                argument == "-D" -> arguments.getOrNull(++index)?.let(defines::add)
                argument.startsWith("-D") && argument.length > 2 -> defines += argument.substring(2)
            }
            index += 1
        }

        return ExplainedArguments(
            compilerPath = arguments.firstOrNull(),
            languageStandard = languageStandard,
            targetTriple = targetTriple,
            sysrootPath = sysrootPath,
            resourceDirectoryPath = resourceDirectoryPath,
            includePaths = includePaths.toList(),
            defines = defines.toList(),
        )
    }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

    private fun resolvePath(path: String, directory: File): String {
        val file = File(path)
        return (if (file.isAbsolute) file else File(directory, path)).stablePath()
    }
}

private fun File.stablePath(): String = runCatching { canonicalPath }.getOrDefault(absolutePath)
