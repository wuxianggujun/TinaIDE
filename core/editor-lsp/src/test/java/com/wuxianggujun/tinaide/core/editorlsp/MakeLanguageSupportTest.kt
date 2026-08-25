package com.wuxianggujun.tinaide.core.editorlsp

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.editorlsp.CompletionSource
import com.wuxianggujun.tinaide.core.textengine.Position
import java.io.File
import org.junit.Test

class MakeLanguageSupportTest {

    @Test
    fun isMakefile_shouldRecognizeCanonicalNamesAndMkExtension() {
        assertThat(MakeLanguageSupport.isMakefile(File("Makefile"))).isTrue()
        assertThat(MakeLanguageSupport.isMakefile(File("GNUmakefile"))).isTrue()
        assertThat(MakeLanguageSupport.isMakefile(File("BSDmakefile"))).isTrue()
        assertThat(MakeLanguageSupport.isMakefile(File("Makefile.in"))).isTrue()
        assertThat(MakeLanguageSupport.isMakefile(File("rules.mk"))).isTrue()
        assertThat(MakeLanguageSupport.isMakefile(File("rules.mak"))).isTrue()
        assertThat(MakeLanguageSupport.isMakefile(File("build.MK"))).isTrue()
        assertThat(MakeLanguageSupport.isMakefile(File("main.cpp"))).isFalse()
    }

    @Test
    fun extractWordPrefix_shouldClampOffsetAndStopAtMakeSyntaxBoundaries() {
        val source = """
            target:
            	@echo $(shell $(VALUE_NAME))
        """.trimIndent()
        val whitespaceSource = "CC := clang\n\n"
        val hyphenSource = "\t@echo $(filter-out src/main.o, $(OBJECTS))"
        val automaticVariableSource = "\t@echo $@"

        val variableOffset = source.indexOf("VALUE_NAME") + "VALUE_NAME".length
        val hyphenOffset = hyphenSource.indexOf("filter-out") + "filter-out".length
        val automaticVariableOffset = automaticVariableSource.indexOf("$@") + "$@".length

        assertThat(MakeLanguageSupport.extractWordPrefix(source, variableOffset))
            .isEqualTo("VALUE_NAME")
        assertThat(MakeLanguageSupport.extractWordPrefix(hyphenSource, hyphenOffset))
            .isEqualTo("filter-out")
        assertThat(MakeLanguageSupport.extractWordPrefix(automaticVariableSource, automaticVariableOffset))
            .isEqualTo("$@")
        assertThat(MakeLanguageSupport.extractWordPrefix(source, -10)).isEmpty()
        assertThat(MakeLanguageSupport.extractWordPrefix(source, source.length + 20))
            .isEqualTo("VALUE_NAME")
        assertThat(MakeLanguageSupport.extractWordPrefix(whitespaceSource, whitespaceSource.length))
            .isEmpty()
    }

    @Test
    fun buildCompletionItems_shouldFallbackToBuiltinSuggestionsWhenSourceTooLarge() {
        val oversizedSource = buildString {
            appendLine("CUSTOM_TARGET := demo")
            append("x".repeat(500_100))
        }

        val builtinItems = MakeLanguageSupport.buildCompletionItems(
            source = oversizedSource,
            prefix = "inc",
            caseSensitive = false,
            completionSource = CompletionSource.LSP
        )
        val customItems = MakeLanguageSupport.buildCompletionItems(
            source = oversizedSource,
            prefix = "CUSTOM",
            caseSensitive = false,
            completionSource = CompletionSource.LSP
        )

        assertThat(builtinItems.map { it.label }).contains("include")
        assertThat(customItems).isEmpty()
    }

    @Test
    fun buildDiagnostics_shouldPreserveSourceAndExpectedLineColumns() {
        val diagnostics = MakeLanguageSupport.buildDiagnostics(
            file = File("Makefile"),
            documentUri = "file:///workspace/Makefile",
            source = """
                ifdef DEBUG
                VALUE := 1
                endef
            """.trimIndent()
        )

        assertThat(diagnostics).hasSize(2)

        val unexpectedEndef = diagnostics.first { it.message.contains("Unexpected endef") }
        val missingEndif = diagnostics.first { it.message.contains("Missing endif") }

        assertThat(unexpectedEndef.fileUri).isEqualTo("file:///workspace/Makefile")
        assertThat(unexpectedEndef.source).isEqualTo("make")
        assertThat(unexpectedEndef.line).isEqualTo(2)
        assertThat(unexpectedEndef.column).isEqualTo(0)
        assertThat(missingEndif.line).isEqualTo(0)
        assertThat(missingEndif.column).isEqualTo(0)
    }

    @Test
    fun buildHoverMarkdown_shouldDescribeSpecialTargetAndBuiltinVariable() {
        val source = """
            .PHONY: clean
            CC := clang
        """.trimIndent()

        val specialTargetHover = MakeLanguageSupport.buildHoverMarkdown(
            file = File("Makefile"),
            documentUri = "file:///Makefile",
            source = source,
            position = Position(line = 0, column = 1)
        )
        val builtinVariableHover = MakeLanguageSupport.buildHoverMarkdown(
            file = File("Makefile"),
            documentUri = "file:///Makefile",
            source = source,
            position = Position(line = 1, column = 1)
        )

        assertThat(specialTargetHover).contains("**Special target**")
        assertThat(specialTargetHover).contains(".PHONY")
        assertThat(builtinVariableHover).contains("**Builtin variable**")
        assertThat(builtinVariableHover).contains("CC")
    }

    @Test
    fun buildHoverMarkdown_shouldIncludeBuiltinDocAndUserDefinedValueForBuiltinVariable() {
        val source = """
            CC := clang++
            all:
            	@echo $(CC)
        """.trimIndent()

        val hover = MakeLanguageSupport.buildHoverMarkdown(
            file = File("Makefile"),
            documentUri = "file:///workspace/Makefile",
            source = source,
            position = Position(line = 2, column = 10)
        )

        assertThat(hover).contains("**Builtin variable**")
        assertThat(hover).contains("CC")
        assertThat(hover).contains("**Variable** value")
        assertThat(hover).contains("Defined at line 1")
        assertThat(hover).contains("clang++")
    }

    @Test
    fun documentSymbolsAndLocations_shouldTrackPhonyTargetsVariablesAndReferences() {
        val source = """
            .PHONY: clean
            all: clean
            CC := clang
            clean:
            	@echo $(CC)
        """.trimIndent()
        val file = File("Makefile")
        val documentUri = "file:///workspace/Makefile"

        val symbols = MakeLanguageSupport.buildDocumentSymbols(
            file = file,
            documentUri = documentUri,
            source = source
        )
        val targetDefinition = MakeLanguageSupport.buildDefinitionLocations(
            file = file,
            documentUri = documentUri,
            source = source,
            position = Position(line = 1, column = 7)
        )
        val variableReferences = MakeLanguageSupport.buildReferenceLocations(
            file = file,
            documentUri = documentUri,
            source = source,
            position = Position(line = 4, column = 10)
        )

        assertThat(symbols.map { it.name }).containsAtLeast("all", "clean", "CC")
        assertThat(symbols.first { it.name == "all" }.kind).isEqualTo("Target")
        assertThat(symbols.first { it.name == "CC" }.kind).isEqualTo("Variable")
        assertThat(symbols.first { it.name == "clean" }.containerName).isEqualTo("Phony")
        assertThat(targetDefinition.single().line).isEqualTo(3)
        assertThat(variableReferences.map { it.line }).containsExactly(2, 4).inOrder()
    }
}
