package com.wuxianggujun.tinaide.core.lsp

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Before
import org.junit.Test

class CxxCompileContextInspectorTest {
    private lateinit var projectRoot: File
    private lateinit var buildDir: File

    @Before
    fun setUp() {
        projectRoot = Files.createTempDirectory("cxx-context-").toFile()
        buildDir = File(projectRoot, "build").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        projectRoot.deleteRecursively()
    }

    @Test
    fun inspect_extractsExactCompileCommandDetails() {
        val sourceFile = File(projectRoot, "src/main.cpp").apply {
            parentFile?.mkdirs()
            writeText("int main() { return 0; }\n", Charsets.UTF_8)
        }
        File(projectRoot, "include").mkdirs()
        File(projectRoot, "third_party/include").mkdirs()
        writeDatabase(
            """
            [{
              "directory":"${jsonPath(projectRoot)}",
              "arguments":[
                "/toolchain/bin/clang++",
                "-std=c++23",
                "--target=aarch64-linux-android28",
                "--sysroot","sysroot",
                "-resource-dir=toolchain/lib/clang/19",
                "-Iinclude",
                "-isystem","third_party/include",
                "-DDEBUG=1",
                "-D","FEATURE_ENABLED",
                "${jsonPath(sourceFile)}"
              ],
              "file":"${jsonPath(sourceFile)}"
            }]
            """.trimIndent(),
        )

        val context = CxxCompileContextInspector.inspect(
            prepared = prepared(sourceFile, CxxCompileDatabaseSource.EXTERNAL),
            compileCommandsDir = buildDir,
        )

        assertThat(context.issue).isNull()
        assertThat(context.commandMatch).isEqualTo(CxxCompileCommandMatch.EXACT)
        assertThat(context.compileDatabaseSource).isEqualTo(CxxCompileDatabaseSource.EXTERNAL)
        assertThat(context.compilerPath).isEqualTo("/toolchain/bin/clang++")
        assertThat(context.languageStandard).isEqualTo("c++23")
        assertThat(context.targetTriple).isEqualTo("aarch64-linux-android28")
        assertThat(context.sysrootPath).isEqualTo(File(projectRoot, "sysroot").canonicalPath)
        assertThat(context.resourceDirectoryPath)
            .isEqualTo(File(projectRoot, "toolchain/lib/clang/19").canonicalPath)
        assertThat(context.includePaths).containsExactly(
            File(projectRoot, "include").canonicalPath,
            File(projectRoot, "third_party/include").canonicalPath,
        ).inOrder()
        assertThat(context.defines).containsExactly("DEBUG=1", "FEATURE_ENABLED").inOrder()
    }

    @Test
    fun inspect_infersHeaderCommandFromMatchingSourceStem() {
        val headerFile = File(projectRoot, "include/widget.hpp").apply {
            parentFile?.mkdirs()
            writeText("class Widget {};\n", Charsets.UTF_8)
        }
        val sourceFile = File(projectRoot, "src/widget.cpp").apply {
            parentFile?.mkdirs()
            writeText("#include \"widget.hpp\"\n", Charsets.UTF_8)
        }
        writeDatabase(
            """
            [{
              "directory":"${jsonPath(projectRoot)}",
              "command":"clang++ -std=c++20 -Iinclude -c src/widget.cpp",
              "file":"src/widget.cpp"
            }]
            """.trimIndent(),
        )

        val context = CxxCompileContextInspector.inspect(
            prepared = prepared(headerFile, CxxCompileDatabaseSource.TINA_FALLBACK),
            compileCommandsDir = buildDir,
        )

        assertThat(context.issue).isNull()
        assertThat(context.commandMatch).isEqualTo(CxxCompileCommandMatch.INFERRED)
        assertThat(context.matchedSourcePath).isEqualTo(sourceFile.canonicalPath)
        assertThat(context.languageStandard).isEqualTo("c++20")
        assertThat(context.includePaths).containsExactly(File(projectRoot, "include").canonicalPath)
    }

    @Test
    fun inspect_reportsInvalidDatabaseWithoutThrowing() {
        val sourceFile = File(projectRoot, "main.cpp").apply {
            writeText("int main() {}\n", Charsets.UTF_8)
        }
        writeDatabase("{not-json")

        val context = CxxCompileContextInspector.inspect(
            prepared = prepared(sourceFile, CxxCompileDatabaseSource.TINA_FALLBACK),
            compileCommandsDir = buildDir,
        )

        assertThat(context.issue).isEqualTo(CxxCompileContextIssue.COMPILE_DATABASE_INVALID)
        assertThat(context.commandMatch).isNull()
    }

    private fun prepared(
        file: File,
        source: CxxCompileDatabaseSource,
    ): CompileDatabaseProvider.Prepared = CompileDatabaseProvider.Prepared(
        file = file,
        workspaceRoot = projectRoot,
        projectType = CompileDatabaseProvider.ProjectType.CMAKE_PROJECT,
        compileCommandsDir = buildDir,
        sourceCompileCommandsDir = buildDir,
        compileDatabaseSource = source,
        shouldGenerate = false,
        scanRoot = projectRoot,
        isCxx = true,
        desiredCppStandardFlag = "c++20",
        packageFingerprint = "packages",
        toolchainId = "toolchain",
        sysrootProfileId = "sysroot-profile",
        sysrootApiLevel = 28,
    )

    private fun writeDatabase(content: String) {
        File(buildDir, "compile_commands.json").writeText(content, Charsets.UTF_8)
    }

    private fun jsonPath(file: File): String = file.canonicalPath.replace("\\", "\\\\")
}
