package com.wuxianggujun.tinaide.core.lsp

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class CompileCommandsGeneratorPackageIncludeTest {

    @Test
    fun `generate includes installed package include directories for clangd`() {
        val tempDir = createTempDirectory(prefix = "compile-commands-package-include-").toFile()
        try {
            val projectRoot = File(tempDir, "project").apply { mkdirs() }
            val packageInclude = File(tempDir, "installed-packages/nlohmann-json/include").apply { mkdirs() }
            val sourceFile = File(projectRoot, "main.cpp").apply {
                writeText(
                    """
                    #include <nlohmann/json.hpp>
                    int main() { return 0; }
                    """.trimIndent()
                )
            }
            val outputFile = File(projectRoot, "build/debug/compile_commands.json")

            CompileCommandsGenerator.generate(
                projectPath = projectRoot.absolutePath,
                sysrootDir = null,
                sourceFiles = listOf(sourceFile.absolutePath),
                includeDirs = listOf(packageInclude.absolutePath),
                isCxx = true,
                clangppPathOverride = "/toolchain/bin/clang++",
                outputFileOverride = outputFile
            )

            val arguments = Json.parseToJsonElement(outputFile.readText())
                .jsonArray
                .single()
                .jsonObject["arguments"]!!
                .jsonArray
                .map { it.jsonPrimitive.contentOrNull.orEmpty() }

            assertThat(arguments).contains("-I${packageInclude.absolutePath}")
            assertThat(arguments).contains("-std=c++17")
            assertThat(arguments).contains(sourceFile.canonicalPath)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `generate preserves string cpp standard flag`() {
        val tempDir = createTempDirectory(prefix = "compile-commands-standard-").toFile()
        try {
            val sourceFile = File(tempDir, "main.cpp").apply {
                writeText("int main() { return 0; }\n")
            }
            val outputFile = File(tempDir, "build/compile_commands.json")

            CompileCommandsGenerator.generate(
                projectPath = tempDir.absolutePath,
                sysrootDir = null,
                sourceFiles = listOf(sourceFile.absolutePath),
                includeDirs = emptyList(),
                cppStandardFlag = "gnu++26",
                extraCppFlags = listOf("-Wall", "-std=c++11"),
                outputFileOverride = outputFile,
            )

            val arguments = Json.parseToJsonElement(outputFile.readText())
                .jsonArray
                .single()
                .jsonObject["arguments"]!!
                .jsonArray
                .map { it.jsonPrimitive.contentOrNull.orEmpty() }

            assertThat(arguments).contains("-std=gnu++26")
            assertThat(arguments.filter { it.startsWith("-std=") }.last()).isEqualTo("-std=gnu++26")
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
