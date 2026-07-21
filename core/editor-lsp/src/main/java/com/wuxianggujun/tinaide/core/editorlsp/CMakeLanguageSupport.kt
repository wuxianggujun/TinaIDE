package com.wuxianggujun.tinaide.core.editorlsp

import com.wuxianggujun.tinaide.cmake.CMake
import com.wuxianggujun.tinaide.cmake.CMakeDoc
import com.wuxianggujun.tinaide.cmake.analysis.AnalysisResult
import com.wuxianggujun.tinaide.cmake.analysis.CMakeAnalyzer
import com.wuxianggujun.tinaide.cmake.analysis.TargetAnalysis
import com.wuxianggujun.tinaide.cmake.analysis.VariableInfo
import com.wuxianggujun.tinaide.cmake.parser.CMakeLexer
import com.wuxianggujun.tinaide.cmake.parser.CMakeParser
import com.wuxianggujun.tinaide.cmake.parser.CommandInvocation
import com.wuxianggujun.tinaide.cmake.parser.Token
import com.wuxianggujun.tinaide.core.editorlsp.CompletionItem
import com.wuxianggujun.tinaide.core.editorlsp.CompletionItemKind
import com.wuxianggujun.tinaide.core.editorlsp.CompletionSource
import com.wuxianggujun.tinaide.core.editorlsp.SemanticToken
import com.wuxianggujun.tinaide.core.lsp.Diagnostic
import com.wuxianggujun.tinaide.core.lsp.DocumentSymbolItem
import com.wuxianggujun.tinaide.core.lsp.LocationItem
import com.wuxianggujun.tinaide.core.textengine.Position
import java.io.File
import java.util.ArrayDeque
import kotlin.math.max

object CMakeLanguageSupport {

    private const val MAX_PARSE_SIZE = 500_000

    val keywords: Set<String> = CMAKE_KEYWORDS

    fun isCMakeFile(file: File): Boolean = file.name.equals("CMakeLists.txt", ignoreCase = true) ||
        file.extension.equals("cmake", ignoreCase = true)

    fun buildCompletionItems(
        source: String?,
        prefix: String,
        completionSource: CompletionSource
    ): List<CompletionItem> {
        val items = mutableListOf<CompletionItem>()

        CMAKE_COMMAND_NAMES.asSequence()
            .filter { it.matchesPrefix(prefix) }
            .mapTo(items) { command ->
                CompletionItem(
                    label = command,
                    kind = CompletionItemKind.FUNCTION,
                    detail = "CMake",
                    insertText = "$command()",
                    source = completionSource
                )
            }

        CMAKE_SUBCOMMAND_KEYWORDS.asSequence()
            .filter { it.matchesPrefix(prefix) }
            .mapTo(items) { keyword ->
                CompletionItem(
                    label = keyword,
                    kind = CompletionItemKind.KEYWORD,
                    detail = "CMake",
                    insertText = keyword,
                    source = completionSource
                )
            }

        CMAKE_BUILTIN_VARIABLES.asSequence()
            .filter { it.matchesPrefix(prefix) }
            .mapTo(items) { variable ->
                CompletionItem(
                    label = variable,
                    kind = CompletionItemKind.VARIABLE,
                    detail = "CMake",
                    insertText = variable,
                    source = completionSource
                )
            }

        val parsed = parseContext(source) ?: return items

        parsed.analysis.variables.values.asSequence()
            .filter { it.name.matchesPrefix(prefix) }
            .mapTo(items) { variable ->
                CompletionItem(
                    label = variable.name,
                    kind = CompletionItemKind.VARIABLE,
                    detail = variable.values.firstOrNull()
                        ?: variable.docstring
                        ?: variable.cacheType
                        ?: "Variable",
                    insertText = variable.name,
                    source = completionSource
                )
            }

        parsed.analysis.targets.values.asSequence()
            .filter { it.name.matchesPrefix(prefix) }
            .mapTo(items) { target ->
                CompletionItem(
                    label = target.name,
                    kind = CompletionItemKind.MODULE,
                    detail = target.type.name,
                    insertText = target.name,
                    source = completionSource
                )
            }

        parsed.analysis.functions.values.asSequence()
            .filter { it.name.matchesPrefix(prefix) }
            .mapTo(items) { function ->
                CompletionItem(
                    label = function.name,
                    kind = CompletionItemKind.FUNCTION,
                    detail = "function(${function.arguments.joinToString(", ")})",
                    insertText = "${function.name}()",
                    source = completionSource
                )
            }

        parsed.analysis.macros.values.asSequence()
            .filter { it.name.matchesPrefix(prefix) }
            .mapTo(items) { macro ->
                CompletionItem(
                    label = macro.name,
                    kind = CompletionItemKind.FUNCTION,
                    detail = "macro(${macro.arguments.joinToString(", ")})",
                    insertText = "${macro.name}()",
                    source = completionSource
                )
            }

        return items.distinctBy { it.label.lowercase() }
    }

    fun buildSemanticTokens(source: String): List<SemanticToken> {
        val parsed = parseContext(source) ?: return emptyList()
        val mapper = OffsetMapper(parsed.source)
        val macroNames = parsed.analysis.macros.keys.mapTo(HashSet()) { it.lowercase() }
        val functionNames = parsed.analysis.functions.keys.mapTo(HashSet()) { it.lowercase() }
        val variableNames = parsed.analysis.variables.keys
        val targetNames = parsed.analysis.targets.keys
        val tokens = mutableListOf<SemanticToken>()

        parsed.doc.rawCommands.forEach { command ->
            addSemanticToken(
                tokens = tokens,
                mapper = mapper,
                offset = command.startOffset,
                length = command.originalIdentifier.length.coerceAtLeast(1),
                tokenType = when {
                    command.normalizedIdentifier() in macroNames -> "macro"
                    command.normalizedIdentifier() in functionNames -> "function"
                    else -> "function"
                }
            )

            command.arguments.forEachIndexed { index, argument ->
                val tokenType = classifyArgument(
                    command = command,
                    argumentIndex = index,
                    argument = argument,
                    variableNames = variableNames,
                    targetNames = targetNames
                ) ?: return@forEachIndexed
                addSemanticToken(
                    tokens = tokens,
                    mapper = mapper,
                    offset = argument.startOffset,
                    length = (argument.endOffset - argument.startOffset).coerceAtLeast(argument.text.length).coerceAtLeast(1),
                    tokenType = tokenType
                )
            }
        }

        return tokens.distinct()
    }

    fun buildDiagnostics(
        file: File,
        documentUri: String,
        source: String
    ): List<Diagnostic> {
        if (source.length > MAX_PARSE_SIZE) return emptyList()
        val parsed = parseContext(source)
        if (parsed == null) {
            val error = CMake.parse(source).exceptionOrNull() ?: return emptyList()
            return listOf(createParseDiagnostic(file, documentUri, source, error))
        }

        val mapper = OffsetMapper(parsed.source)
        return parsed.analysis.diagnostics.map { diagnostic ->
            val command = parsed.doc.rawCommands.getOrNull(diagnostic.commandIndex)
            val startOffset = command?.startOffset ?: 0
            val endOffsetExclusive = command?.endOffset?.coerceAtLeast(startOffset + 1)
                ?: (startOffset + 1)
            val start = mapper.lineColumnAt(startOffset)
            val end = mapper.lineColumnAt((endOffsetExclusive - 1).coerceAtLeast(startOffset))
            Diagnostic(
                fileUri = documentUri,
                fileName = file.name,
                line = start.first,
                column = start.second,
                endLine = end.first,
                endColumn = end.second + 1,
                message = diagnostic.message,
                severity = diagnostic.level.toEditorSeverity(),
                source = "cmake"
            )
        }
    }

    fun buildDocumentSymbols(
        file: File,
        documentUri: String,
        source: String
    ): List<DocumentSymbolItem> {
        val parsed = parseContext(source) ?: return emptyList()
        val filePath = file.absolutePath
        val mapper = OffsetMapper(parsed.source)
        val items = mutableListOf<DocumentSymbolItem>()
        val scopeStack = ArrayDeque<SymbolScope>()

        parsed.doc.rawCommands.forEach { command ->
            val containerName = scopeStack.lastOrNull()?.name
            val level = scopeStack.size

            when (command.normalizedIdentifier()) {
                "project" -> {
                    command.arguments.getOrNull(0)?.let { token ->
                        items += command.toDocumentSymbolItem(
                            mapper = mapper,
                            filePath = filePath,
                            fileName = file.name,
                            uri = documentUri,
                            token = token,
                            name = token.text,
                            kind = "Project",
                            containerName = containerName,
                            level = level
                        )
                    }
                }

                "add_executable", "add_library", "add_custom_target" -> {
                    command.arguments.getOrNull(0)?.let { token ->
                        items += command.toDocumentSymbolItem(
                            mapper = mapper,
                            filePath = filePath,
                            fileName = file.name,
                            uri = documentUri,
                            token = token,
                            name = token.text,
                            kind = "Target",
                            containerName = containerName,
                            level = level
                        )
                    }
                }

                "set", "option" -> {
                    command.arguments.getOrNull(0)?.let { token ->
                        items += command.toDocumentSymbolItem(
                            mapper = mapper,
                            filePath = filePath,
                            fileName = file.name,
                            uri = documentUri,
                            token = token,
                            name = token.text,
                            kind = "Variable",
                            containerName = containerName,
                            level = level
                        )
                    }
                }

                "function" -> {
                    val nameToken = command.arguments.getOrNull(0) ?: return@forEach
                    items += command.toDocumentSymbolItem(
                        mapper = mapper,
                        filePath = filePath,
                        fileName = file.name,
                        uri = documentUri,
                        token = nameToken,
                        name = nameToken.text,
                        kind = "Function",
                        containerName = containerName,
                        level = level
                    )
                    command.arguments.drop(1).forEach { argument ->
                        items += command.toDocumentSymbolItem(
                            mapper = mapper,
                            filePath = filePath,
                            fileName = file.name,
                            uri = documentUri,
                            token = argument,
                            name = argument.text,
                            kind = "Parameter",
                            containerName = nameToken.text,
                            level = level + 1
                        )
                    }
                    scopeStack.addLast(SymbolScope(name = nameToken.text, type = "function"))
                }

                "macro" -> {
                    val nameToken = command.arguments.getOrNull(0) ?: return@forEach
                    items += command.toDocumentSymbolItem(
                        mapper = mapper,
                        filePath = filePath,
                        fileName = file.name,
                        uri = documentUri,
                        token = nameToken,
                        name = nameToken.text,
                        kind = "Macro",
                        containerName = containerName,
                        level = level
                    )
                    command.arguments.drop(1).forEach { argument ->
                        items += command.toDocumentSymbolItem(
                            mapper = mapper,
                            filePath = filePath,
                            fileName = file.name,
                            uri = documentUri,
                            token = argument,
                            name = argument.text,
                            kind = "Parameter",
                            containerName = nameToken.text,
                            level = level + 1
                        )
                    }
                    scopeStack.addLast(SymbolScope(name = nameToken.text, type = "macro"))
                }

                "endfunction" -> popScope(scopeStack, "function")
                "endmacro" -> popScope(scopeStack, "macro")
            }
        }

        return items
    }

    fun buildDefinitionLocations(
        file: File,
        documentUri: String,
        source: String,
        position: Position
    ): List<LocationItem> {
        val parsed = parseContext(source) ?: return emptyList()
        val mapper = OffsetMapper(parsed.source)
        val occurrences = buildSymbolOccurrences(
            file = file,
            documentUri = documentUri,
            parsed = parsed,
            mapper = mapper
        )
        val offset = mapper.offsetFor(position.line, position.column)
        val current = occurrences.firstOrNull { it.contains(offset) } ?: return emptyList()
        return occurrences.asSequence()
            .filter { it.symbolKey == current.symbolKey && it.role == SymbolRole.DEFINITION }
            .map { it.toLocationItem(mapper) }
            .distinctBy { "${it.filePath}:${it.line}:${it.column}:${it.endLine}:${it.endColumn}" }
            .toList()
    }

    fun buildReferenceLocations(
        file: File,
        documentUri: String,
        source: String,
        position: Position
    ): List<LocationItem> {
        val parsed = parseContext(source) ?: return emptyList()
        val mapper = OffsetMapper(parsed.source)
        val occurrences = buildSymbolOccurrences(
            file = file,
            documentUri = documentUri,
            parsed = parsed,
            mapper = mapper
        )
        val offset = mapper.offsetFor(position.line, position.column)
        val current = occurrences.firstOrNull { it.contains(offset) } ?: return emptyList()
        return occurrences.asSequence()
            .filter { it.symbolKey == current.symbolKey }
            .map { it.toLocationItem(mapper) }
            .distinctBy { "${it.filePath}:${it.line}:${it.column}:${it.endLine}:${it.endColumn}" }
            .toList()
    }

    fun buildHoverMarkdown(
        file: File,
        documentUri: String,
        source: String,
        position: Position
    ): String? {
        val parsed = parseContext(source) ?: return null
        val mapper = OffsetMapper(parsed.source)
        val offset = mapper.offsetFor(position.line, position.column)
        val occurrences = buildSymbolOccurrences(
            file = file,
            documentUri = documentUri,
            parsed = parsed,
            mapper = mapper
        )
        val current = occurrences.firstOrNull { it.contains(offset) }
        if (current != null) {
            return buildOccurrenceHoverMarkdown(current, parsed)
        }
        val command = parsed.doc.rawCommands.firstOrNull { candidate ->
            val identifierEnd = candidate.startOffset + candidate.originalIdentifier.length
            offset in candidate.startOffset until identifierEnd
        } ?: return null
        return buildCommandHoverMarkdown(command)
    }

    fun positionToOffset(source: String, position: Position): Int = OffsetMapper(source).offsetFor(position.line, position.column)

    fun extractWordPrefix(source: String, offset: Int): String {
        val safeOffset = offset.coerceIn(0, source.length)
        val start = max(0, safeOffset - 128)
        val window = source.substring(start, safeOffset)
        var end = window.length - 1
        while (end >= 0 && !isWordChar(window[end])) {
            if (window[end].isWhitespace()) return ""
            end--
        }
        if (end < 0) return ""
        var index = end
        while (index >= 0 && isWordChar(window[index])) {
            index--
        }
        return window.substring(index + 1, end + 1)
    }

    private fun classifyArgument(
        command: CommandInvocation,
        argumentIndex: Int,
        argument: Token,
        variableNames: Set<String>,
        targetNames: Set<String>
    ): String? {
        val text = argument.text
        val normalizedCommand = command.normalizedIdentifier()
        val uppercaseText = text.uppercase()

        if (text.isBlank()) return null
        if (argument.quoted) return "string"
        if (CMAKE_NUMERIC_LITERAL.matches(text)) return "number"
        if (uppercaseText in CMAKE_BOOLEAN_LITERALS) return "enumMember"
        if (uppercaseText in CMAKE_SUBCOMMAND_KEYWORDS) return "keyword"
        if (text.isCMakeVariableReference() || (text.startsWith("ENV{") && text.endsWith("}"))) {
            return "variable"
        }

        when (normalizedCommand) {
            "set", "unset", "option" -> if (argumentIndex == 0) return "variable"
            "function" -> {
                if (argumentIndex == 0) return "function"
                return "parameter"
            }
            "macro" -> {
                if (argumentIndex == 0) return "macro"
                return "parameter"
            }
            "project" -> if (argumentIndex == 0) return "namespace"
            "find_package" -> if (argumentIndex == 0) return "namespace"
        }

        if (normalizedCommand in TARGET_DEFINITION_COMMANDS && argumentIndex == 0) {
            return "class"
        }
        if (normalizedCommand in TARGET_REFERENCE_COMMANDS && argumentIndex == 0 && text in targetNames) {
            return "class"
        }
        if (text.startsWith("CMAKE_") || text.startsWith("PROJECT_") || text in variableNames) {
            return "variable"
        }

        return null
    }

    private fun addSemanticToken(
        tokens: MutableList<SemanticToken>,
        mapper: OffsetMapper,
        offset: Int,
        length: Int,
        tokenType: String
    ) {
        if (length <= 0) return
        val (line, column) = mapper.lineColumnAt(offset)
        tokens += SemanticToken(
            line = line,
            startColumn = column,
            length = length,
            tokenType = tokenType
        )
    }

    private fun parseContext(source: String?): ParsedContext? {
        if (source == null || source.length > MAX_PARSE_SIZE) return null
        val doc = CMake.parse(source).getOrNull() ?: return null
        val analysis = runCatching { CMakeAnalyzer(doc).analyze() }.getOrNull() ?: return null
        return ParsedContext(source = source, doc = doc, analysis = analysis)
    }

    private fun String.matchesPrefix(prefix: String): Boolean {
        if (prefix.isBlank()) return true
        return startsWith(prefix, ignoreCase = true) && !equals(prefix, ignoreCase = true)
    }

    private fun buildSymbolOccurrences(
        file: File,
        documentUri: String,
        parsed: ParsedContext,
        mapper: OffsetMapper
    ): List<SymbolOccurrence> {
        val occurrences = mutableListOf<SymbolOccurrence>()
        val targetNames = parsed.analysis.targets.keys
        val functionNames = parsed.analysis.functions.keys.mapTo(HashSet()) { it.lowercase() }
        val macroNames = parsed.analysis.macros.keys.mapTo(HashSet()) { it.lowercase() }

        parsed.doc.rawCommands.forEach { command ->
            val identifierRangeEnd = command.startOffset + command.originalIdentifier.length
            val identifierName = command.normalizedIdentifier()

            if (identifierName in functionNames) {
                occurrences += SymbolOccurrence(
                    symbolKey = "function:$identifierName",
                    role = SymbolRole.REFERENCE,
                    name = command.originalIdentifier,
                    file = file,
                    documentUri = documentUri,
                    startOffset = command.startOffset,
                    endOffset = identifierRangeEnd,
                )
            } else if (identifierName in macroNames) {
                occurrences += SymbolOccurrence(
                    symbolKey = "macro:$identifierName",
                    role = SymbolRole.REFERENCE,
                    name = command.originalIdentifier,
                    file = file,
                    documentUri = documentUri,
                    startOffset = command.startOffset,
                    endOffset = identifierRangeEnd,
                )
            }

            command.arguments.forEachIndexed { index, token ->
                val variableReferenceName = token.text.extractVariableReferenceName()
                when {
                    command.normalizedIdentifier() in VARIABLE_DEFINITION_COMMANDS && index == 0 -> {
                        occurrences += token.toOccurrence(
                            symbolKey = "variable:${token.text}",
                            role = SymbolRole.DEFINITION,
                            file = file,
                            documentUri = documentUri,
                        )
                    }

                    variableReferenceName != null -> {
                        occurrences += token.toOccurrence(
                            symbolKey = "variable:$variableReferenceName",
                            role = SymbolRole.REFERENCE,
                            file = file,
                            documentUri = documentUri,
                        )
                    }

                    command.normalizedIdentifier() in TARGET_DEFINITION_COMMANDS && index == 0 -> {
                        occurrences += token.toOccurrence(
                            symbolKey = "target:${token.text}",
                            role = SymbolRole.DEFINITION,
                            file = file,
                            documentUri = documentUri,
                        )
                    }

                    command.normalizedIdentifier() == "function" && index == 0 -> {
                        val lowered = token.text.lowercase()
                        occurrences += token.toOccurrence(
                            symbolKey = "function:$lowered",
                            role = SymbolRole.DEFINITION,
                            file = file,
                            documentUri = documentUri,
                        )
                    }

                    command.normalizedIdentifier() == "macro" && index == 0 -> {
                        val lowered = token.text.lowercase()
                        occurrences += token.toOccurrence(
                            symbolKey = "macro:$lowered",
                            role = SymbolRole.DEFINITION,
                            file = file,
                            documentUri = documentUri,
                        )
                    }

                    token.text in targetNames && command.normalizedIdentifier() in TARGET_REFERENCE_COMMANDS -> {
                        occurrences += token.toOccurrence(
                            symbolKey = "target:${token.text}",
                            role = SymbolRole.REFERENCE,
                            file = file,
                            documentUri = documentUri,
                        )
                    }
                }
            }
        }

        return occurrences.sortedWith(compareBy<SymbolOccurrence> { it.startOffset }.thenBy { it.endOffset })
    }

    private fun buildOccurrenceHoverMarkdown(
        occurrence: SymbolOccurrence,
        parsed: ParsedContext
    ): String? = when {
        occurrence.symbolKey.startsWith("variable:") -> {
            val name = occurrence.symbolKey.removePrefix("variable:")
            buildVariableHoverMarkdown(name = name, variable = parsed.analysis.variables[name])
        }

        occurrence.symbolKey.startsWith("target:") -> {
            val name = occurrence.symbolKey.removePrefix("target:")
            parsed.analysis.targets[name]?.toHoverMarkdown(name)
        }

        occurrence.symbolKey.startsWith("function:") -> {
            val name = occurrence.symbolKey.removePrefix("function:")
            parsed.analysis.functions.valueForName(name)?.toHoverMarkdown(kind = "Function")
        }

        occurrence.symbolKey.startsWith("macro:") -> {
            val name = occurrence.symbolKey.removePrefix("macro:")
            parsed.analysis.macros.valueForName(name)?.toHoverMarkdown(kind = "Macro")
        }

        else -> null
    }

    private fun buildVariableHoverMarkdown(
        name: String,
        variable: VariableInfo?
    ): String? {
        if (variable == null) {
            return if (name in CMAKE_BUILTIN_VARIABLES) {
                buildString {
                    append("**Builtin Variable** ")
                    append(name.asMarkdownCode())
                }
            } else {
                null
            }
        }
        return buildString {
            append("**Variable** ")
            append(variable.name.asMarkdownCode())
            variable.values.takeIf { it.isNotEmpty() }?.let { values ->
                append("\n\nValue: ")
                append(values.joinToString(" ").asMarkdownCode())
            }
            append("\n\nScope: ")
            append(variable.scope.name.asMarkdownCode())
            if (variable.isCache) {
                append("\n\nCache: ")
                append((variable.cacheType ?: "UNSPECIFIED").asMarkdownCode())
            }
            variable.docstring?.takeIf { it.isNotBlank() }?.let { doc ->
                append("\n\n")
                append(doc.trim())
            }
        }
    }

    private fun TargetAnalysis.toHoverMarkdown(name: String): String = buildString {
        append("**Target** ")
        append(name.asMarkdownCode())
        append("\n\nType: ")
        append(type.name.asMarkdownCode())
        sources.takeIf { it.isNotEmpty() }?.let {
            append("\n\nSources: ")
            append(it.joinToString(", ").asMarkdownCode())
        }
        dependencies.takeIf { it.isNotEmpty() }?.let {
            append("\n\nDependencies: ")
            append(it.joinToString(", ").asMarkdownCode())
        }
        linkLibraries.map { it.library }.distinct().takeIf { it.isNotEmpty() }?.let {
            append("\n\nLinked: ")
            append(it.joinToString(", ").asMarkdownCode())
        }
    }

    private fun CMakeAnalyzer.FunctionDefinition.toHoverMarkdown(kind: String): String = buildString {
        append("**")
        append(kind)
        append("** ")
        append(name.asMarkdownCode())
        append("\n\nSignature: ")
        append("$name(${arguments.joinToString(", ")})".asMarkdownCode())
    }

    private fun CMakeAnalyzer.MacroDefinition.toHoverMarkdown(kind: String): String = buildString {
        append("**")
        append(kind)
        append("** ")
        append(name.asMarkdownCode())
        append("\n\nSignature: ")
        append("$name(${arguments.joinToString(", ")})".asMarkdownCode())
    }

    private fun buildCommandHoverMarkdown(command: CommandInvocation): String = buildString {
        append("**Command** ")
        append(command.originalIdentifier.asMarkdownCode())
        if (command.arguments.isNotEmpty()) {
            append("\n\nArguments: ")
            append(command.arguments.joinToString(" ") { it.text }.asMarkdownCode())
        }
    }

    private fun <T> Map<String, T>.valueForName(name: String): T? = entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value

    private fun String.asMarkdownCode(): String = "`" + replace("`", "\\`") + "`"

    private fun Token.toOccurrence(
        symbolKey: String,
        role: SymbolRole,
        file: File,
        documentUri: String,
    ): SymbolOccurrence = SymbolOccurrence(
        symbolKey = symbolKey,
        role = role,
        name = text,
        file = file,
        documentUri = documentUri,
        startOffset = startOffset,
        endOffset = endOffset.coerceAtLeast(startOffset + text.length.coerceAtLeast(1)),
    )

    private fun String.isCMakeVariableReference(): Boolean = extractVariableReferenceName() != null

    private fun String.extractVariableReferenceName(): String? {
        // 避免 ICU/JDK 正则差异在类初始化阶段抛异常，直接做字符串解析。
        val prefix = "\${"
        if (!startsWith(prefix) || !endsWith("}")) return null
        if (length <= prefix.length + 1) return null
        val name = substring(prefix.length, length - 1).trim()
        return name.takeIf { it.isNotEmpty() && '{' !in it && '}' !in it }
    }

    private fun com.wuxianggujun.tinaide.cmake.analysis.Diagnostic.Level.toEditorSeverity(): Diagnostic.Severity = when (this) {
        com.wuxianggujun.tinaide.cmake.analysis.Diagnostic.Level.ERROR -> Diagnostic.Severity.ERROR
        com.wuxianggujun.tinaide.cmake.analysis.Diagnostic.Level.WARNING -> Diagnostic.Severity.WARNING
        com.wuxianggujun.tinaide.cmake.analysis.Diagnostic.Level.INFO -> Diagnostic.Severity.INFO
        com.wuxianggujun.tinaide.cmake.analysis.Diagnostic.Level.HINT -> Diagnostic.Severity.HINT
    }

    private fun createParseDiagnostic(
        file: File,
        documentUri: String,
        source: String,
        error: Throwable
    ): Diagnostic {
        val mapper = OffsetMapper(source)
        val offset = error.toOffset(source.length)
        val position = mapper.lineColumnAt(offset)
        return Diagnostic(
            fileUri = documentUri,
            fileName = file.name,
            line = position.first,
            column = position.second,
            endLine = position.first,
            endColumn = position.second + 1,
            message = error.message ?: "CMake parse failed",
            severity = Diagnostic.Severity.ERROR,
            source = "cmake-parser"
        )
    }

    private fun Throwable.toOffset(sourceLength: Int): Int = when (this) {
        is CMakeParser.ParseError.ExpectedChar -> pos.coerceIn(0, sourceLength)
        is CMakeParser.ParseError.ExpectedIdentifier -> pos.coerceIn(0, sourceLength)
        is CMakeParser.ParseError.UnexpectedEOF -> sourceLength
        is CMakeParser.ParseError.LexerError -> lexerCause.toOffset(sourceLength)
        else -> 0
    }

    private fun CMakeLexer.LexerError.toOffset(sourceLength: Int): Int = when (this) {
        is CMakeLexer.LexerError.UnterminatedString -> startPos.coerceIn(0, sourceLength)
        is CMakeLexer.LexerError.UnterminatedBracket -> startPos.coerceIn(0, sourceLength)
        is CMakeLexer.LexerError.InvalidEscape -> pos.coerceIn(0, sourceLength)
        is CMakeLexer.LexerError.UnexpectedChar -> pos.coerceIn(0, sourceLength)
    }

    private fun CommandInvocation.toDocumentSymbolItem(
        mapper: OffsetMapper,
        filePath: String,
        fileName: String,
        uri: String,
        token: Token,
        name: String,
        kind: String,
        containerName: String?,
        level: Int
    ): DocumentSymbolItem {
        val start = mapper.lineColumnAt(token.startOffset)
        val endOffset = if (token.endOffset > token.startOffset) token.endOffset - 1 else token.startOffset
        val end = mapper.lineColumnAt(endOffset)
        return DocumentSymbolItem(
            name = name,
            kind = kind,
            containerName = containerName,
            uri = uri,
            filePath = filePath,
            fileName = fileName,
            line = start.first,
            column = start.second,
            endLine = end.first,
            endColumn = end.second + 1,
            level = level
        )
    }

    private fun popScope(stack: ArrayDeque<SymbolScope>, expectedType: String) {
        while (stack.isNotEmpty()) {
            val removed = stack.removeLast()
            if (removed.type == expectedType) {
                return
            }
        }
    }

    private fun isWordChar(char: Char): Boolean = char == '_' || char.isLetterOrDigit()

    private data class ParsedContext(
        val source: String,
        val doc: CMakeDoc,
        val analysis: AnalysisResult
    )

    private data class SymbolScope(
        val name: String,
        val type: String
    )

    private enum class SymbolRole {
        DEFINITION,
        REFERENCE
    }

    private data class SymbolOccurrence(
        val symbolKey: String,
        val role: SymbolRole,
        val name: String,
        val file: File,
        val documentUri: String,
        val startOffset: Int,
        val endOffset: Int,
    ) {
        fun contains(offset: Int): Boolean = offset in startOffset until endOffset

        fun toLocationItem(mapper: OffsetMapper): LocationItem {
            val start = mapper.lineColumnAt(startOffset)
            val safeEnd = if (endOffset > startOffset) endOffset - 1 else startOffset
            val end = mapper.lineColumnAt(safeEnd)
            return LocationItem(
                uri = documentUri,
                filePath = file.absolutePath,
                fileName = file.name,
                line = start.first,
                column = start.second,
                endLine = end.first,
                endColumn = end.second + 1,
                previewText = mapper.lineText(start.first)?.trim()
            )
        }
    }

    private class OffsetMapper(source: String) {
        private val sourceText: String = source
        private val lineStarts: IntArray
        private val textLength: Int = source.length

        init {
            val starts = ArrayList<Int>()
            starts += 0
            source.forEachIndexed { index, char ->
                if (char == '\n') {
                    starts += index + 1
                }
            }
            lineStarts = starts.toIntArray()
        }

        fun lineColumnAt(offset: Int): Pair<Int, Int> {
            val safeOffset = offset.coerceIn(0, textLength)
            val lineIndex = lineStarts.binarySearch(safeOffset).let { found ->
                when {
                    found >= 0 -> found
                    else -> (-found - 2).coerceAtLeast(0)
                }
            }
            return lineIndex to (safeOffset - lineStarts[lineIndex])
        }

        fun offsetFor(line: Int, column: Int): Int {
            if (lineStarts.isEmpty()) return 0
            val safeLine = line.coerceIn(0, lineStarts.lastIndex)
            val lineStart = lineStarts[safeLine]
            val lineEndExclusive = if (safeLine == lineStarts.lastIndex) {
                textLength
            } else {
                (lineStarts[safeLine + 1] - 1).coerceAtLeast(lineStart)
            }
            val lineLength = (lineEndExclusive - lineStart).coerceAtLeast(0)
            return lineStart + column.coerceIn(0, lineLength)
        }

        fun lineText(line: Int): String? {
            if (lineStarts.isEmpty()) return null
            val safeLine = line.takeIf { it in 0..lineStarts.lastIndex } ?: return null
            val start = lineStarts[safeLine]
            val endExclusive = if (safeLine == lineStarts.lastIndex) {
                textLength
            } else {
                (lineStarts[safeLine + 1] - 1).coerceAtLeast(start)
            }
            return sourceText.substring(start, endExclusive)
        }
    }
}

private val TARGET_DEFINITION_COMMANDS: Set<String> = linkedSetOf(
    "add_executable",
    "add_library",
    "add_custom_target",
)

private val TARGET_REFERENCE_COMMANDS: Set<String> = linkedSetOf(
    "target_link_libraries",
    "target_include_directories",
    "target_compile_definitions",
    "target_compile_options",
    "target_compile_features",
    "target_sources",
    "target_link_directories",
    "target_link_options",
    "add_dependencies",
    "set_target_properties",
    "get_target_property",
)

private val VARIABLE_DEFINITION_COMMANDS: Set<String> = linkedSetOf(
    "set",
    "unset",
    "option",
)

private val CMAKE_BOOLEAN_LITERALS: Set<String> = linkedSetOf(
    "TRUE",
    "FALSE",
    "ON",
    "OFF",
    "YES",
    "NO"
)

private val CMAKE_NUMERIC_LITERAL = Regex("""^[+-]?(?:\d+\.?\d*|\.\d+)$""")

private val CMAKE_COMMAND_NAMES: Set<String> = linkedSetOf(
    "set", "unset", "message", "if", "elseif", "else", "endif",
    "foreach", "endforeach", "while", "endwhile",
    "function", "endfunction", "macro", "endmacro",
    "block", "endblock", "break", "continue", "return",
    "include", "cmake_minimum_required", "cmake_policy", "option",
    "find_package", "find_library", "find_path", "find_file", "find_program",
    "list", "string", "file", "math", "execute_process", "configure_file",
    "cmake_parse_arguments", "mark_as_advanced", "get_filename_component",
    "project", "add_executable", "add_library",
    "target_link_libraries", "target_include_directories",
    "target_compile_definitions", "target_compile_options",
    "target_compile_features", "target_sources",
    "target_link_directories", "target_link_options",
    "add_subdirectory", "add_dependencies",
    "add_custom_command", "add_custom_target",
    "add_test", "enable_testing", "try_compile", "try_run",
    "install", "include_directories", "link_directories", "link_libraries",
    "add_compile_definitions", "add_compile_options", "add_link_options",
    "set_property", "get_property", "set_target_properties", "get_target_property",
    "export",
)

private val CMAKE_SUBCOMMAND_KEYWORDS: Set<String> = linkedSetOf(
    "VERSION", "REQUIRED", "QUIET", "CONFIG", "MODULE", "COMPONENTS",
    "PUBLIC", "PRIVATE", "INTERFACE",
    "STATIC", "SHARED", "OBJECT", "IMPORTED", "ALIAS",
    "DESTINATION", "TARGETS", "FILES", "DIRECTORY",
    "COMMAND", "WORKING_DIRECTORY", "DEPENDS", "OUTPUT", "COMMENT",
    "CACHE", "PARENT_SCOPE", "FORCE",
    "PROPERTIES", "PROPERTY", "APPEND", "APPEND_STRING",
    "TRUE", "FALSE", "ON", "OFF", "YES", "NO",
)

private val CMAKE_BUILTIN_VARIABLES: Set<String> = linkedSetOf(
    "CMAKE_SOURCE_DIR", "CMAKE_BINARY_DIR",
    "CMAKE_CURRENT_SOURCE_DIR", "CMAKE_CURRENT_BINARY_DIR",
    "CMAKE_CXX_STANDARD", "CMAKE_C_STANDARD",
    "CMAKE_BUILD_TYPE", "CMAKE_INSTALL_PREFIX", "CMAKE_PREFIX_PATH",
    "CMAKE_CXX_FLAGS", "CMAKE_C_FLAGS",
    "CMAKE_MODULE_PATH", "CMAKE_FIND_PACKAGE_RESOLVE_SYMLINKS",
    "CMAKE_POSITION_INDEPENDENT_CODE",
    "CMAKE_LIBRARY_OUTPUT_DIRECTORY", "CMAKE_RUNTIME_OUTPUT_DIRECTORY",
    "CMAKE_SYSTEM_NAME", "CMAKE_SYSTEM_PROCESSOR",
    "CMAKE_ANDROID_NDK", "CMAKE_ANDROID_API", "CMAKE_ANDROID_ARCH_ABI",
    "CMAKE_TOOLCHAIN_FILE",
    "PROJECT_NAME", "PROJECT_VERSION", "PROJECT_SOURCE_DIR", "PROJECT_BINARY_DIR",
)

private val CMAKE_KEYWORDS: Set<String> =
    CMAKE_COMMAND_NAMES + CMAKE_SUBCOMMAND_KEYWORDS + CMAKE_BUILTIN_VARIABLES
