package com.wuxianggujun.tinaide.core.editorlsp

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.editorlsp.CompletionFetchResult
import com.wuxianggujun.tinaide.core.textengine.Position
import com.wuxianggujun.tinaide.core.textengine.TextChange
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test

class MakeLanguageServiceSessionTest {

    @Test
    fun requestCompletion_shouldReturnBuiltinAndAnalyzedSymbols() = runBlocking {
        val source = listOf(
            "CC := clang",
            ".PHONY: clean",
            "clean:",
            "\t@echo ${'$'}(CC)",
            "all: clean",
            ""
        ).joinToString("\n")

        val session = MakeLanguageServiceSession(
            file = File("Makefile"),
            documentUri = "file:///Makefile",
            textProvider = { source },
            caseSensitiveProvider = { false }
        )

        val result = session.requestCompletion(
            position = Position(line = 5, column = 0),
            triggerChar = null
        )

        assertThat(result).isInstanceOf(CompletionFetchResult.Success::class.java)

        val items = (result as CompletionFetchResult.Success).items
        assertThat(items.map { it.label }).containsAtLeast("include", "CC", "clean", "all")
        assertThat(items.all { it.source.name == "LSP" }).isTrue()
    }

    @Test
    fun documentSymbols_shouldExposeVariablesAndPhonyTargets() {
        val source = """
            .PHONY: clean
            CC := clang
            clean:
            	@echo ${'$'}(CC)
            all: clean
        """.trimIndent()

        val session = MakeLanguageServiceSession(
            file = File("Makefile"),
            documentUri = "file:///Makefile",
            textProvider = { source }
        )

        val symbols = session.documentSymbols()

        assertThat(symbols.map { it.name }).containsAtLeast("CC", "clean", "all")
        assertThat(symbols.first { it.name == "CC" }.kind).isEqualTo("Variable")
        assertThat(symbols.first { it.name == "clean" }.kind).isEqualTo("Target")
        assertThat(symbols.first { it.name == "clean" }.containerName).isEqualTo("Phony")
    }

    @Test
    fun gotoDefinition_shouldResolveVariableAndTargetDefinitions() {
        val source = """
            CC := clang
            clean:
            	@echo ${'$'}(CC)
            all: clean
        """.trimIndent()

        val session = MakeLanguageServiceSession(
            file = File("Makefile"),
            documentUri = "file:///Makefile",
            textProvider = { source }
        )

        val variableDefinition = session.gotoDefinition(Position(line = 2, column = 10))
        val targetDefinition = session.gotoDefinition(Position(line = 3, column = 6))

        assertThat(variableDefinition.single().line).isEqualTo(0)
        assertThat(targetDefinition.single().line).isEqualTo(1)
    }

    @Test
    fun findReferences_shouldReturnDefinitionsAndReferencesForKnownSymbols() {
        val source = """
            CC := clang
            clean:
            	@echo ${'$'}(CC)
            all: clean
            	@printf ${'$'}(CC)
        """.trimIndent()

        val session = MakeLanguageServiceSession(
            file = File("Makefile"),
            documentUri = "file:///Makefile",
            textProvider = { source }
        )

        val variableRefs = session.findReferences(Position(line = 0, column = 1))
        val targetRefs = session.findReferences(Position(line = 1, column = 1))

        assertThat(variableRefs.map { it.line }).containsExactly(0, 2, 4).inOrder()
        assertThat(targetRefs.map { it.line }).containsExactly(1, 3).inOrder()
    }

    @Test
    fun hover_shouldReturnVariableAndTargetInfo() {
        val source = """
            CC := clang
            clean:
            	@echo ${'$'}(CC)
            all: clean
        """.trimIndent()

        val session = MakeLanguageServiceSession(
            file = File("Makefile"),
            documentUri = "file:///Makefile",
            textProvider = { source }
        )

        val variableHover = session.hover(Position(line = 2, column = 10))
        val targetHover = session.hover(Position(line = 3, column = 6))

        assertThat(variableHover).contains("**Variable**")
        assertThat(variableHover).contains("CC")
        assertThat(variableHover).contains("clang")
        assertThat(targetHover).contains("**Target**")
        assertThat(targetHover).contains("clean")
    }

    @Test
    fun currentDiagnostics_shouldReportUnexpectedEndefAndMissingEndif() {
        val source = """
            ifdef DEBUG
            CC := clang
            endef
        """.trimIndent()

        val session = MakeLanguageServiceSession(
            file = File("Makefile"),
            documentUri = "file:///Makefile",
            textProvider = { source }
        )

        val diagnostics = session.currentDiagnostics()

        assertThat(diagnostics).hasSize(2)
        assertThat(diagnostics.map { it.message }).contains("Unexpected endef without matching define block.")
        assertThat(diagnostics.map { it.message }).contains("Missing endif for `ifdef` block.")
        assertThat(diagnostics.all { it.severity.name == "WARNING" }).isTrue()
    }

    @Test
    fun requestCompletion_shouldHonorCaseSensitivitySetting() = runBlocking {
        val source = """
            CC := clang
            all:
            	@echo done
            c
        """.trimIndent()

        val insensitiveSession = MakeLanguageServiceSession(
            file = File("Makefile"),
            documentUri = "file:///Makefile",
            textProvider = { source },
            caseSensitiveProvider = { false }
        )
        val sensitiveSession = MakeLanguageServiceSession(
            file = File("Makefile"),
            documentUri = "file:///Makefile",
            textProvider = { source },
            caseSensitiveProvider = { true }
        )

        val insensitiveLabels = (
            (
                insensitiveSession.requestCompletion(
                    position = Position(line = 3, column = 1),
                    triggerChar = null
                )
                ) as CompletionFetchResult.Success
            ).items.map { it.label }
        val sensitiveLabels = (
            (
                sensitiveSession.requestCompletion(
                    position = Position(line = 3, column = 1),
                    triggerChar = null
                )
                ) as CompletionFetchResult.Success
            ).items.map { it.label }

        assertThat(insensitiveLabels).contains("CC")
        assertThat(sensitiveLabels).doesNotContain("CC")
        assertThat(sensitiveLabels).contains("call")
    }

    @Test
    fun requestCompletion_shouldPreserveHyphenatedFunctionPrefix() = runBlocking {
        val completionLine = "\t@echo $(filter-o"
        val session = MakeLanguageServiceSession(
            file = File("Makefile.in"),
            documentUri = "file:///workspace/Makefile.in",
            textProvider = {
                listOf(
                    "OBJECTS := src/main.o src/util.o",
                    "all:",
                    completionLine
                ).joinToString("\n")
            }
        )

        val result = session.requestCompletion(
            position = Position(line = 2, column = completionLine.length),
            triggerChar = null
        )

        assertThat(result).isInstanceOf(CompletionFetchResult.Success::class.java)
        assertThat((result as CompletionFetchResult.Success).items.map { it.label })
            .contains("filter-out")
    }

    @Test
    fun hover_shouldDescribeBuiltinFunctionAndBuiltinVariable() {
        val source = """
            all:
            	@echo $(shell pwd)
            CC
        """.trimIndent()

        val session = MakeLanguageServiceSession(
            file = File("Makefile"),
            documentUri = "file:///Makefile",
            textProvider = { source }
        )

        val functionHover = session.hover(Position(line = 1, column = 10))
        val builtinVariableHover = session.hover(Position(line = 2, column = 1))

        assertThat(functionHover).contains("**Function**")
        assertThat(functionHover).contains("shell")
        assertThat(builtinVariableHover).contains("**Builtin variable**")
        assertThat(builtinVariableHover).contains("CC")
    }

    @Test
    fun hover_shouldDescribeAutomaticVariable() {
        val source = """
            all: input.txt
            	cp $< $@
        """.trimIndent()
        val automaticVariableColumn = source.lineSequence().elementAt(1).indexOf("${'$'}@")

        val session = MakeLanguageServiceSession(
            file = File("rules.mak"),
            documentUri = "file:///workspace/rules.mak",
            textProvider = { source }
        )

        val hover = session.hover(Position(line = 1, column = automaticVariableColumn))

        assertThat(hover).contains("**Automatic variable**")
        assertThat(hover).contains("${'$'}@")
    }

    @Test
    fun findReferences_shouldIgnoreBuiltinFunctionNameButKeepVariableReferences() {
        val source = """
            FILES := b a
            all:
            	@echo $(sort $(FILES))
        """.trimIndent()

        val session = MakeLanguageServiceSession(
            file = File("Makefile"),
            documentUri = "file:///Makefile",
            textProvider = { source }
        )

        val variableRefs = session.findReferences(Position(line = 0, column = 1))
        val functionDefinition = session.gotoDefinition(Position(line = 2, column = 9))

        assertThat(variableRefs.map { it.line }).containsExactly(0, 2).inOrder()
        assertThat(functionDefinition).isEmpty()
    }

    @Test
    fun documentSymbols_shouldExposeMultipleTargetsAndPhonyMembership() {
        val source = """
            .PHONY: clean install
            clean install: prepare
            	@echo done
            prepare:
            	@echo prepare
        """.trimIndent()

        val session = MakeLanguageServiceSession(
            file = File("Makefile"),
            documentUri = "file:///Makefile",
            textProvider = { source }
        )

        val symbols = session.documentSymbols()

        assertThat(symbols.map { it.name }).containsAtLeast("clean", "install", "prepare")
        assertThat(symbols.first { it.name == "clean" }.containerName).isEqualTo("Phony")
        assertThat(symbols.first { it.name == "install" }.containerName).isEqualTo("Phony")
        assertThat(symbols.first { it.name == "prepare" }.containerName).isNull()
    }

    @Test
    fun didChange_shouldInvalidateSnapshotsAndRefreshDiagnosticsAndSymbols() {
        var source = """
            all:
            	@echo ok
        """.trimIndent()

        val session = MakeLanguageServiceSession(
            file = File("Makefile"),
            documentUri = "file:///Makefile",
            textProvider = { source }
        )

        assertThat(session.currentDiagnostics()).isEmpty()
        assertThat(session.documentSymbols().map { it.name }).contains("all")

        source = """
            ifdef DEBUG
            value := 1
        """.trimIndent()
        session.didChange(
            TextChange(
                startOffset = 0,
                endOffset = 0,
                oldText = "",
                newText = source,
                startLine = 0,
                startColumn = 0,
                endLine = 0,
                endColumn = 0
            )
        )

        assertThat(session.currentDiagnostics().map { it.message })
            .contains("Missing endif for `ifdef` block.")
        assertThat(session.documentSymbols().map { it.name }).contains("value")
    }

    @Test
    fun close_shouldDisableFurtherLanguageServiceResponses() = runBlocking {
        val session = MakeLanguageServiceSession(
            file = File("Makefile"),
            documentUri = "file:///Makefile",
            textProvider = { "all:\n\t@echo ok\n" }
        )

        session.close()

        val completion = session.requestCompletion(
            position = Position(line = 0, column = 0),
            triggerChar = null
        )

        assertThat(completion).isInstanceOf(CompletionFetchResult.TransientFailure::class.java)
        assertThat(session.currentDiagnostics()).isEmpty()
        assertThat(session.documentSymbols()).isEmpty()
        assertThat(session.hover(Position(line = 0, column = 1))).isNull()
    }
}
