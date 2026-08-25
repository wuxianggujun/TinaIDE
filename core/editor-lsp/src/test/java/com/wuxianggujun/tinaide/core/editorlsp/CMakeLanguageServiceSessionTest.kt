package com.wuxianggujun.tinaide.core.editorlsp

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.editorlsp.CompletionFetchResult
import com.wuxianggujun.tinaide.core.textengine.Position
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test

class CMakeLanguageServiceSessionTest {

    @Test
    fun requestCompletion_shouldReturnBuiltinAndAnalyzedSymbols() = runBlocking {
        val source = """
            cmake_minimum_required(VERSION 3.22)
            project(Demo)
            set(APP_NAME demo)
            add_library(core STATIC main.cpp)
            function(register_demo target_name)
            endfunction()
            message(APP)
            message(reg)
            message(add_)
        """.trimIndent()

        val session = CMakeLanguageServiceSession(
            file = File("CMakeLists.txt"),
            documentUri = "file:///CMakeLists.txt",
            textProvider = { source }
        )

        val variableResult = session.requestCompletion(
            position = Position(line = 6, column = 11),
            triggerChar = null
        )
        val functionResult = session.requestCompletion(
            position = Position(line = 7, column = 11),
            triggerChar = null
        )
        val builtinResult = session.requestCompletion(
            position = Position(line = 8, column = 12),
            triggerChar = null
        )

        assertThat(variableResult).isInstanceOf(CompletionFetchResult.Success::class.java)
        assertThat(functionResult).isInstanceOf(CompletionFetchResult.Success::class.java)
        assertThat(builtinResult).isInstanceOf(CompletionFetchResult.Success::class.java)

        val variableItems = (variableResult as CompletionFetchResult.Success).items
        val functionItems = (functionResult as CompletionFetchResult.Success).items
        val builtinItems = (builtinResult as CompletionFetchResult.Success).items

        assertThat(variableItems.map { it.label }).contains("APP_NAME")
        assertThat(functionItems.map { it.label }).contains("register_demo")
        assertThat(builtinItems.map { it.label }).contains("add_library")
        assertThat((variableItems + functionItems + builtinItems).all { it.source.name == "LSP" }).isTrue()
    }

    @Test
    fun requestSemanticTokens_shouldClassifyCmakeTokensWithoutDefaultingToVariable() = runBlocking {
        val source = """
            cmake_minimum_required(VERSION 3.22)
            project(Demo)
            set(APP_NAME "demo")
            add_library(core STATIC main.cpp)
            target_link_libraries(core PRIVATE ${'$'}{APP_NAME})
        """.trimIndent()

        val session = CMakeLanguageServiceSession(
            file = File("CMakeLists.txt"),
            documentUri = "file:///CMakeLists.txt",
            textProvider = { source }
        )

        val tokens = session.requestSemanticTokens()

        assertThat(tokens).isNotEmpty()
        assertThat(tokens.any { it.tokenType == "function" && it.line == 0 }).isTrue()
        assertThat(tokens.any { it.tokenType == "keyword" && it.line == 0 }).isTrue()
        assertThat(tokens.any { it.tokenType == "string" && it.line == 2 }).isTrue()
        assertThat(tokens.any { it.tokenType == "variable" && it.line == 2 }).isTrue()
        assertThat(tokens.any { it.tokenType == "class" && it.line == 3 }).isTrue()
        assertThat(tokens.none { it.line == 3 && it.tokenType == "variable" && it.length == "main.cpp".length }).isTrue()
    }

    @Test
    fun currentDiagnostics_shouldMapAnalyzerWarningsToEditorDiagnostics() {
        val source = """
            target_link_libraries(missingTarget PRIVATE demo)
        """.trimIndent()

        val session = CMakeLanguageServiceSession(
            file = File("CMakeLists.txt"),
            documentUri = "file:///CMakeLists.txt",
            textProvider = { source }
        )

        val diagnostics = session.currentDiagnostics()

        assertThat(diagnostics).isNotEmpty()
        assertThat(diagnostics.first().severity.name).isEqualTo("WARNING")
        assertThat(diagnostics.first().message).contains("target")
        assertThat(diagnostics.first().line).isEqualTo(0)
    }

    @Test
    fun documentSymbols_shouldExposeTargetsFunctionsParametersAndVariables() {
        val source = """
            project(Demo)
            set(APP_NAME demo)
            function(register_demo target_name output_dir)
              set(INTERNAL_FLAG ON)
            endfunction()
            add_library(core STATIC main.cpp)
        """.trimIndent()

        val session = CMakeLanguageServiceSession(
            file = File("CMakeLists.txt"),
            documentUri = "file:///CMakeLists.txt",
            textProvider = { source }
        )

        val symbols = session.documentSymbols()

        assertThat(symbols.map { it.name }).containsAtLeast(
            "Demo",
            "APP_NAME",
            "register_demo",
            "target_name",
            "output_dir",
            "INTERNAL_FLAG",
            "core"
        )
        assertThat(symbols.first { it.name == "register_demo" }.kind).isEqualTo("Function")
        assertThat(symbols.first { it.name == "target_name" }.level).isEqualTo(1)
        assertThat(symbols.first { it.name == "INTERNAL_FLAG" }.containerName).isEqualTo("register_demo")
    }

    @Test
    fun gotoDefinition_shouldResolveVariableFunctionAndTargetDefinitions() {
        val source = """
            set(APP_NAME demo)
            function(register_demo target_name)
            endfunction()
            add_library(core STATIC main.cpp)
            register_demo(${'$'}{APP_NAME})
            target_link_libraries(core PRIVATE helper)
            message(${'$'}{APP_NAME})
        """.trimIndent()

        val session = CMakeLanguageServiceSession(
            file = File("CMakeLists.txt"),
            documentUri = "file:///CMakeLists.txt",
            textProvider = { source }
        )

        val variableDefinition = session.gotoDefinition(Position(line = 4, column = 18))
        val functionDefinition = session.gotoDefinition(Position(line = 4, column = 2))
        val targetDefinition = session.gotoDefinition(Position(line = 5, column = 22))

        assertThat(variableDefinition.single().line).isEqualTo(0)
        assertThat(functionDefinition.single().line).isEqualTo(1)
        assertThat(targetDefinition.single().line).isEqualTo(3)
    }

    @Test
    fun findReferences_shouldReturnDefinitionAndReferencesForKnownSymbols() {
        val source = """
            set(APP_NAME demo)
            function(register_demo target_name)
            endfunction()
            add_library(core STATIC main.cpp)
            register_demo(${'$'}{APP_NAME})
            target_link_libraries(core PRIVATE helper)
            message(${'$'}{APP_NAME})
        """.trimIndent()

        val session = CMakeLanguageServiceSession(
            file = File("CMakeLists.txt"),
            documentUri = "file:///CMakeLists.txt",
            textProvider = { source }
        )

        val variableRefs = session.findReferences(Position(line = 0, column = 5))
        val functionRefs = session.findReferences(Position(line = 1, column = 11))
        val targetRefs = session.findReferences(Position(line = 3, column = 12))

        assertThat(variableRefs.map { it.line }).containsExactly(0, 4, 6).inOrder()
        assertThat(functionRefs.map { it.line }).containsExactly(1, 4).inOrder()
        assertThat(targetRefs.map { it.line }).containsExactly(3, 5).inOrder()
    }

    @Test
    fun findReferences_shouldIgnoreMalformedVariableReferenceTokens() {
        val source = """
            set(APP_NAME demo)
            message(${'$'}{APP_NAME})
            message(${'$'}{APP_NAME}})
        """.trimIndent()

        val session = CMakeLanguageServiceSession(
            file = File("CMakeLists.txt"),
            documentUri = "file:///CMakeLists.txt",
            textProvider = { source }
        )

        val variableRefs = session.findReferences(Position(line = 0, column = 5))

        assertThat(variableRefs.map { it.line }).containsExactly(0, 1).inOrder()
    }

    @Test
    fun hover_shouldReturnVariableInfo() {
        val source = """
            set(APP_NAME demo)
            message(${'$'}{APP_NAME})
        """.trimIndent()

        val session = CMakeLanguageServiceSession(
            file = File("CMakeLists.txt"),
            documentUri = "file:///CMakeLists.txt",
            textProvider = { source }
        )

        val hover = session.hover(Position(line = 1, column = 12))

        assertThat(hover).contains("**Variable**")
        assertThat(hover).contains("APP_NAME")
        assertThat(hover).contains("demo")
    }

    @Test
    fun hover_shouldReturnTargetInfo() {
        val source = """
            add_library(core STATIC main.cpp)
            target_link_libraries(core PRIVATE helper)
        """.trimIndent()

        val session = CMakeLanguageServiceSession(
            file = File("CMakeLists.txt"),
            documentUri = "file:///CMakeLists.txt",
            textProvider = { source }
        )

        val hover = session.hover(Position(line = 1, column = 22))

        assertThat(hover).contains("**Target**")
        assertThat(hover).contains("core")
        assertThat(hover).contains("STATIC_LIBRARY")
    }

    @Test
    fun hover_shouldReturnFunctionInfo() {
        val source = """
            function(register_demo target_name output_dir)
            endfunction()
            register_demo(app build)
        """.trimIndent()

        val session = CMakeLanguageServiceSession(
            file = File("CMakeLists.txt"),
            documentUri = "file:///CMakeLists.txt",
            textProvider = { source }
        )

        val hover = session.hover(Position(line = 2, column = 3))

        assertThat(hover).contains("**Function**")
        assertThat(hover).contains("register_demo")
        assertThat(hover).contains("target_name, output_dir")
    }
}
