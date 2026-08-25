package com.wuxianggujun.tinaide.core.editorlsp

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.editorlsp.CompletionSource
import com.wuxianggujun.tinaide.core.textengine.Position
import java.io.File
import org.junit.Test

class CMakeLanguageSupportTest {

    @Test
    fun isCMakeFile_shouldRecognizeCanonicalNamesAndExtension() {
        assertThat(CMakeLanguageSupport.isCMakeFile(File("CMakeLists.txt"))).isTrue()
        assertThat(CMakeLanguageSupport.isCMakeFile(File("module.cmake"))).isTrue()
        assertThat(CMakeLanguageSupport.isCMakeFile(File("toolchain.CMAKE"))).isTrue()
        assertThat(CMakeLanguageSupport.isCMakeFile(File("Makefile"))).isFalse()
    }

    @Test
    fun extractWordPrefix_shouldClampOffsetAndSkipTrailingSyntax() {
        val source = """
            project(Demo)
            message(${'$'}{APP_NAME}))
        """.trimIndent()

        val variableOffset = source.indexOf("APP_NAME") + "APP_NAME".length

        assertThat(CMakeLanguageSupport.extractWordPrefix(source, variableOffset))
            .isEqualTo("APP_NAME")
        assertThat(CMakeLanguageSupport.extractWordPrefix(source, -8)).isEmpty()
        assertThat(CMakeLanguageSupport.extractWordPrefix(source, source.length + 16))
            .isEqualTo("APP_NAME")
    }

    @Test
    fun buildCompletionItems_shouldFallbackToBuiltinSuggestionsWhenSourceTooLarge() {
        val oversizedSource = buildString {
            appendLine("set(ZZZ_CUSTOM_NAME demo)")
            append("x".repeat(500_100))
        }

        val builtinItems = CMakeLanguageSupport.buildCompletionItems(
            source = oversizedSource,
            prefix = "add_",
            completionSource = CompletionSource.LSP
        )
        val customItems = CMakeLanguageSupport.buildCompletionItems(
            source = oversizedSource,
            prefix = "ZZZ_",
            completionSource = CompletionSource.LSP
        )

        assertThat(builtinItems.map { it.label }).contains("add_library")
        assertThat(customItems).isEmpty()
    }

    @Test
    fun buildDocumentSymbols_shouldPreserveScopeAndKinds() {
        val source = """
            project(Demo)
            set(APP_NAME demo)
            function(register_demo target_name output_dir)
              add_library(core STATIC main.cpp)
            endfunction()
            macro(set_mode mode)
            endmacro()
        """.trimIndent()

        val symbols = CMakeLanguageSupport.buildDocumentSymbols(
            file = File("CMakeLists.txt"),
            documentUri = "file:///workspace/CMakeLists.txt",
            source = source
        )

        assertThat(symbols.map { it.name }).containsAtLeast(
            "Demo",
            "APP_NAME",
            "register_demo",
            "target_name",
            "output_dir",
            "core",
            "set_mode",
            "mode"
        )
        assertThat(symbols.first { it.name == "Demo" }.kind).isEqualTo("Project")
        assertThat(symbols.first { it.name == "core" }.containerName).isEqualTo("register_demo")
        assertThat(symbols.first { it.name == "core" }.level).isEqualTo(1)
        assertThat(symbols.first { it.name == "set_mode" }.kind).isEqualTo("Macro")
        assertThat(symbols.first { it.name == "mode" }.containerName).isEqualTo("set_mode")
        assertThat(symbols.first { it.name == "mode" }.level).isEqualTo(1)
    }

    @Test
    fun buildHoverMarkdown_shouldDescribeBuiltinVariableAndCommand() {
        val source = """
            message(${'$'}{CMAKE_SOURCE_DIR})
            message(STATUS demo)
        """.trimIndent()

        val builtinVariableHover = CMakeLanguageSupport.buildHoverMarkdown(
            file = File("CMakeLists.txt"),
            documentUri = "file:///workspace/CMakeLists.txt",
            source = source,
            position = Position(line = 0, column = 11)
        )
        val commandHover = CMakeLanguageSupport.buildHoverMarkdown(
            file = File("CMakeLists.txt"),
            documentUri = "file:///workspace/CMakeLists.txt",
            source = source,
            position = Position(line = 1, column = 2)
        )

        assertThat(builtinVariableHover).contains("**Builtin Variable**")
        assertThat(builtinVariableHover).contains("CMAKE_SOURCE_DIR")
        assertThat(commandHover).contains("**Command**")
        assertThat(commandHover).contains("message")
        assertThat(commandHover).contains("STATUS demo")
    }
}
