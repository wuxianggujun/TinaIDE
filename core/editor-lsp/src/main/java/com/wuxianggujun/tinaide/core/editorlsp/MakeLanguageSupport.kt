package com.wuxianggujun.tinaide.core.editorlsp

import com.wuxianggujun.tinaide.core.editorlsp.CompletionItem
import com.wuxianggujun.tinaide.core.editorlsp.CompletionItemKind
import com.wuxianggujun.tinaide.core.editorlsp.CompletionSource
import com.wuxianggujun.tinaide.core.editorlsp.SemanticToken
import com.wuxianggujun.tinaide.core.lang.MakeFileSupport
import com.wuxianggujun.tinaide.core.lsp.Diagnostic
import com.wuxianggujun.tinaide.core.lsp.DocumentSymbolItem
import com.wuxianggujun.tinaide.core.lsp.LocationItem
import com.wuxianggujun.tinaide.core.textengine.Position
import java.io.File
import java.util.ArrayDeque
import kotlin.math.max

object MakeLanguageSupport {

    private const val MAX_PARSE_SIZE = 500_000

    val keywords: Set<String> = MAKEFILE_KEYWORDS

    fun isMakefile(file: File): Boolean = MakeFileSupport.isMakeLikeFile(file)

    fun buildCompletionItems(
        source: String?,
        prefix: String,
        caseSensitive: Boolean,
        completionSource: CompletionSource
    ): List<CompletionItem> {
        val items = mutableListOf<CompletionItem>()

        MAKEFILE_DIRECTIVES.asSequence()
            .filter { it.matchesMakePrefix(prefix, caseSensitive) }
            .mapTo(items) { directive ->
                CompletionItem(
                    label = directive,
                    kind = CompletionItemKind.KEYWORD,
                    detail = "Directive",
                    insertText = directive,
                    source = completionSource
                )
            }

        MAKEFILE_SPECIAL_TARGETS.asSequence()
            .filter { it.matchesMakePrefix(prefix, caseSensitive) }
            .mapTo(items) { target ->
                CompletionItem(
                    label = target,
                    kind = CompletionItemKind.KEYWORD,
                    detail = "Special target",
                    insertText = target,
                    source = completionSource
                )
            }

        MAKEFILE_FUNCTIONS.asSequence()
            .filter { it.matchesMakePrefix(prefix, caseSensitive) }
            .mapTo(items) { function ->
                CompletionItem(
                    label = function,
                    kind = CompletionItemKind.FUNCTION,
                    detail = "Function",
                    insertText = "${'$'}($function )",
                    source = completionSource
                )
            }

        MAKEFILE_IMPLICIT_VARIABLES.asSequence()
            .filter { it.matchesMakePrefix(prefix, caseSensitive) }
            .mapTo(items) { variable ->
                CompletionItem(
                    label = variable,
                    kind = CompletionItemKind.VARIABLE,
                    detail = "Builtin variable",
                    insertText = variable,
                    source = completionSource
                )
            }

        MAKEFILE_AUTOMATIC_VARIABLES.asSequence()
            .filter { it.matchesMakePrefix(prefix, caseSensitive) }
            .mapTo(items) { variable ->
                CompletionItem(
                    label = variable,
                    kind = CompletionItemKind.VARIABLE,
                    detail = "Automatic variable",
                    insertText = variable,
                    source = completionSource
                )
            }

        val parsed = parseDocument(source) ?: return items.distinctBy { it.label.lowercase() }

        parsed.variables.values.asSequence()
            .filter { it.name.matchesMakePrefix(prefix, caseSensitive) }
            .mapTo(items) { variable ->
                CompletionItem(
                    label = variable.name,
                    kind = CompletionItemKind.VARIABLE,
                    detail = variable.value.ifBlank { "Variable" },
                    insertText = variable.name,
                    source = completionSource
                )
            }

        parsed.targets.values.asSequence()
            .filter { it.name.matchesMakePrefix(prefix, caseSensitive) }
            .mapTo(items) { target ->
                CompletionItem(
                    label = target.name,
                    kind = CompletionItemKind.MODULE,
                    detail = if (target.phony) "Phony target" else "Target",
                    insertText = target.name,
                    source = completionSource
                )
            }

        return items.distinctBy { it.label.lowercase() }
    }

    fun buildSemanticTokens(@Suppress("UNUSED_PARAMETER") source: String): List<SemanticToken> = emptyList()

    fun buildDiagnostics(
        file: File,
        documentUri: String,
        source: String
    ): List<Diagnostic> {
        val parsed = parseDocument(source) ?: return emptyList()
        val mapper = OffsetMapper(parsed.source)
        return parsed.diagnostics.map { diagnostic ->
            val start = mapper.lineColumnAt(diagnostic.startOffset)
            val safeEnd = if (diagnostic.endOffset > diagnostic.startOffset) {
                diagnostic.endOffset - 1
            } else {
                diagnostic.startOffset
            }
            val end = mapper.lineColumnAt(safeEnd)
            Diagnostic(
                fileUri = documentUri,
                fileName = file.name,
                line = start.first,
                column = start.second,
                endLine = end.first,
                endColumn = end.second + 1,
                message = diagnostic.message,
                severity = diagnostic.severity,
                source = "make"
            )
        }
    }

    fun buildDocumentSymbols(
        file: File,
        documentUri: String,
        source: String
    ): List<DocumentSymbolItem> {
        val parsed = parseDocument(source) ?: return emptyList()
        val mapper = OffsetMapper(parsed.source)
        val symbols = mutableListOf<DocumentSymbolItem>()

        parsed.variables.values.forEach { variable ->
            symbols += DocumentSymbolItem(
                name = variable.name,
                kind = "Variable",
                uri = documentUri,
                filePath = file.absolutePath,
                fileName = file.name,
                line = variable.line,
                column = variable.column,
                endLine = variable.endLine,
                endColumn = variable.endColumn,
                level = 0
            )
        }

        parsed.targets.values.forEach { target ->
            val safeEnd = if (target.endOffset > target.startOffset) target.endOffset - 1 else target.startOffset
            val end = mapper.lineColumnAt(safeEnd)
            symbols += DocumentSymbolItem(
                name = target.name,
                kind = "Target",
                containerName = if (target.phony) "Phony" else null,
                uri = documentUri,
                filePath = file.absolutePath,
                fileName = file.name,
                line = target.line,
                column = target.column,
                endLine = end.first,
                endColumn = end.second + 1,
                level = 0
            )
        }

        return symbols.sortedWith(
            compareBy<DocumentSymbolItem> { it.line }
                .thenBy { it.column }
                .thenBy { it.name }
        )
    }

    fun buildDefinitionLocations(
        file: File,
        documentUri: String,
        source: String,
        position: Position
    ): List<LocationItem> {
        val parsed = parseDocument(source) ?: return emptyList()
        val mapper = OffsetMapper(parsed.source)
        val offset = mapper.offsetFor(position.line, position.column)
        val current = parsed.occurrenceAt(offset) ?: return emptyList()
        return parsed.occurrences.asSequence()
            .filter {
                it.symbolType == current.symbolType &&
                    it.name == current.name &&
                    it.role == SymbolRole.DEFINITION
            }
            .map { it.toLocationItem(file, documentUri, mapper) }
            .toList()
    }

    fun buildReferenceLocations(
        file: File,
        documentUri: String,
        source: String,
        position: Position
    ): List<LocationItem> {
        val parsed = parseDocument(source) ?: return emptyList()
        val mapper = OffsetMapper(parsed.source)
        val offset = mapper.offsetFor(position.line, position.column)
        val current = parsed.occurrenceAt(offset) ?: return emptyList()
        return parsed.occurrences.asSequence()
            .filter {
                it.symbolType == current.symbolType &&
                    it.name == current.name
            }
            .map { it.toLocationItem(file, documentUri, mapper) }
            .toList()
    }

    fun buildHoverMarkdown(
        file: File,
        documentUri: String,
        source: String,
        position: Position
    ): String? {
        val parsed = parseDocument(source) ?: return null
        val mapper = OffsetMapper(parsed.source)
        val offset = mapper.offsetFor(position.line, position.column)
        val current = parsed.occurrenceAt(offset)

        current?.let { occurrence ->
            when (occurrence.symbolType) {
                SymbolType.VARIABLE -> {
                    val definition = parsed.variables[occurrence.name]
                    buildDocumentedVariableHover(
                        name = occurrence.name,
                        definition = definition
                    )?.let { return it }
                    if (definition != null) {
                        return buildVariableHover(definition)
                    }
                }

                SymbolType.TARGET -> {
                    val definition = parsed.targets[occurrence.name]
                    if (definition != null) {
                        val prerequisites = if (definition.prerequisites.isEmpty()) {
                            "No explicit prerequisites"
                        } else {
                            definition.prerequisites.joinToString(", ")
                        }
                        return buildString {
                            append("**Target** `")
                            append(definition.name)
                            append("`\n\n")
                            if (definition.phony) {
                                append("Phony target.\n\n")
                            }
                            append("Defined at line ")
                            append(definition.line + 1)
                            append(".\n\nPrerequisites: ")
                            append(prerequisites)
                        }
                    }
                }
            }
        }

        val token = tokenAtOffset(parsed.source, offset) ?: return null
        if (token in MAKEFILE_DIRECTIVES) {
            return "**Directive** `$token`\n\n${MAKEFILE_DIRECTIVE_DOCS[token] ?: "Make built-in directive."}"
        }
        if (token in MAKEFILE_FUNCTIONS) {
            return "**Function** `$token`\n\n${MAKEFILE_FUNCTION_DOCS[token] ?: "Make built-in function."}"
        }
        if (token in MAKEFILE_SPECIAL_TARGETS) {
            return "**Special target** `$token`\n\n${MAKEFILE_SPECIAL_TARGET_DOCS[token] ?: "Controls built-in make behavior."}"
        }
        buildDocumentedVariableHover(name = token)?.let { return it }
        return null
    }

    fun positionToOffset(source: String, position: Position): Int = OffsetMapper(source).offsetFor(position.line, position.column)

    fun extractWordPrefix(source: String, offset: Int): String {
        val safeOffset = offset.coerceIn(0, source.length)
        val start = max(0, safeOffset - 128)
        val window = source.substring(start, safeOffset)
        var end = window.length - 1
        while (end >= 0 && !isMakeCompletionPrefixChar(window[end])) {
            if (window[end].isWhitespace()) return ""
            end--
        }
        if (end < 0) return ""
        var index = end
        while (index >= 0 && isMakeCompletionPrefixChar(window[index])) {
            index--
        }
        return window.substring(index + 1, end + 1)
    }

    private fun parseDocument(source: String?): MakeParsedDocument? {
        if (source == null || source.length > MAX_PARSE_SIZE) return null
        val mapper = OffsetMapper(source)
        val variables = LinkedHashMap<String, VariableDefinition>()
        val targets = LinkedHashMap<String, TargetDefinition>()
        val occurrences = mutableListOf<SymbolOccurrence>()
        val diagnostics = mutableListOf<MakeDiagnostic>()
        val blockStack = ArrayDeque<BlockFrame>()
        val phonyTargets = linkedSetOf<String>()

        source.lineSequence().forEachIndexed { lineIndex, rawLine ->
            val trimmed = rawLine.trim()
            val lineStartOffset = mapper.offsetFor(lineIndex, 0)
            collectVariableReferences(rawLine, lineStartOffset, occurrences)

            if (trimmed.isEmpty()) {
                return@forEachIndexed
            }

            if (!rawLine.startsWith('\t') && !rawLine.startsWith(' ')) {
                handleDirectiveDiagnostics(
                    rawLine = rawLine,
                    trimmed = trimmed,
                    lineStartOffset = lineStartOffset,
                    blockStack = blockStack,
                    diagnostics = diagnostics
                )
                collectDirectiveVariableReference(
                    rawLine = rawLine,
                    trimmed = trimmed,
                    lineStartOffset = lineStartOffset,
                    occurrences = occurrences
                )
                collectVariableDefinition(
                    rawLine = rawLine,
                    trimmed = trimmed,
                    lineIndex = lineIndex,
                    lineStartOffset = lineStartOffset,
                    variables = variables,
                    occurrences = occurrences
                )
                collectTargetDefinitionsAndReferences(
                    rawLine = rawLine,
                    trimmed = trimmed,
                    lineIndex = lineIndex,
                    lineStartOffset = lineStartOffset,
                    targets = targets,
                    occurrences = occurrences,
                    phonyTargets = phonyTargets
                )
            }
        }

        while (blockStack.isNotEmpty()) {
            val frame = blockStack.removeLast()
            diagnostics += MakeDiagnostic(
                startOffset = frame.startOffset,
                endOffset = frame.endOffset,
                message = if (frame.type == BlockType.CONDITIONAL) {
                    "Missing endif for `${frame.keyword}` block."
                } else {
                    "Missing endef for `${frame.keyword}` block."
                },
                severity = Diagnostic.Severity.WARNING
            )
        }

        if (phonyTargets.isNotEmpty()) {
            phonyTargets.forEach { name ->
                val target = targets[name] ?: return@forEach
                targets[name] = target.copy(phony = true)
            }
        }

        return MakeParsedDocument(
            source = source,
            variables = variables,
            targets = targets,
            occurrences = occurrences.sortedWith(
                compareBy<SymbolOccurrence> { it.startOffset }
                    .thenBy { it.endOffset }
                    .thenBy { it.name }
            ),
            diagnostics = diagnostics
        )
    }

    private fun handleDirectiveDiagnostics(
        rawLine: String,
        trimmed: String,
        lineStartOffset: Int,
        blockStack: ArrayDeque<BlockFrame>,
        diagnostics: MutableList<MakeDiagnostic>
    ) {
        when {
            trimmed.startsWith("ifdef ") ||
                trimmed.startsWith("ifndef ") ||
                trimmed.startsWith("ifeq ") ||
                trimmed.startsWith("ifneq ") -> {
                val keyword = trimmed.substringBefore(' ')
                val keywordColumn = rawLine.indexOf(keyword).coerceAtLeast(0)
                blockStack.addLast(
                    BlockFrame(
                        type = BlockType.CONDITIONAL,
                        keyword = keyword,
                        startOffset = lineStartOffset + keywordColumn,
                        endOffset = lineStartOffset + keywordColumn + keyword.length
                    )
                )
            }

            trimmed == "else" || trimmed.startsWith("else ") -> {
                val conditionalFrame = blockStack.lastOrNull { it.type == BlockType.CONDITIONAL }
                if (conditionalFrame == null) {
                    val start = lineStartOffset + rawLine.indexOf("else").coerceAtLeast(0)
                    diagnostics += MakeDiagnostic(
                        startOffset = start,
                        endOffset = start + "else".length,
                        message = "Unexpected else without matching conditional.",
                        severity = Diagnostic.Severity.WARNING
                    )
                }
            }

            trimmed == "endif" || trimmed.startsWith("endif ") -> {
                val conditionalFrame = blockStack.lastOrNull { it.type == BlockType.CONDITIONAL }
                if (conditionalFrame == null) {
                    val start = lineStartOffset + rawLine.indexOf("endif").coerceAtLeast(0)
                    diagnostics += MakeDiagnostic(
                        startOffset = start,
                        endOffset = start + "endif".length,
                        message = "Unexpected endif without matching conditional.",
                        severity = Diagnostic.Severity.WARNING
                    )
                } else {
                    while (blockStack.isNotEmpty()) {
                        val removed = blockStack.removeLast()
                        if (removed === conditionalFrame) break
                    }
                }
            }

            trimmed.startsWith("define ") -> {
                val keywordColumn = rawLine.indexOf("define").coerceAtLeast(0)
                blockStack.addLast(
                    BlockFrame(
                        type = BlockType.DEFINE,
                        keyword = trimmed.substringAfter("define ").substringBefore(' ').ifBlank { "define" },
                        startOffset = lineStartOffset + keywordColumn,
                        endOffset = lineStartOffset + keywordColumn + "define".length
                    )
                )
            }

            trimmed == "endef" || trimmed.startsWith("endef ") -> {
                val defineFrame = blockStack.lastOrNull { it.type == BlockType.DEFINE }
                if (defineFrame == null) {
                    val start = lineStartOffset + rawLine.indexOf("endef").coerceAtLeast(0)
                    diagnostics += MakeDiagnostic(
                        startOffset = start,
                        endOffset = start + "endef".length,
                        message = "Unexpected endef without matching define block.",
                        severity = Diagnostic.Severity.WARNING
                    )
                } else {
                    while (blockStack.isNotEmpty()) {
                        val removed = blockStack.removeLast()
                        if (removed === defineFrame) break
                    }
                }
            }
        }
    }

    private fun collectDirectiveVariableReference(
        rawLine: String,
        trimmed: String,
        lineStartOffset: Int,
        occurrences: MutableList<SymbolOccurrence>
    ) {
        if (!trimmed.startsWith("ifdef ") && !trimmed.startsWith("ifndef ")) return
        val variableName = trimmed.substringAfter(' ').trim()
        if (!variableName.matches(MAKEFILE_SIMPLE_VARIABLE_NAME)) return
        val column = rawLine.indexOf(variableName)
        if (column < 0) return
        occurrences += SymbolOccurrence(
            name = variableName,
            symbolType = SymbolType.VARIABLE,
            role = SymbolRole.REFERENCE,
            startOffset = lineStartOffset + column,
            endOffset = lineStartOffset + column + variableName.length
        )
    }

    private fun collectVariableDefinition(
        rawLine: String,
        trimmed: String,
        lineIndex: Int,
        lineStartOffset: Int,
        variables: MutableMap<String, VariableDefinition>,
        occurrences: MutableList<SymbolOccurrence>
    ) {
        val normalized = normalizeVariableDefinition(trimmed) ?: return
        val match = MAKEFILE_VAR_REGEX.find(normalized) ?: return
        val variableName = match.groupValues[1]
        if (variableName.isBlank()) return
        val column = rawLine.indexOf(variableName)
        if (column < 0) return
        val operatorStart = rawLine.indexOf('=', column)
        val value = if (operatorStart >= 0 && operatorStart + 1 <= rawLine.length) {
            rawLine.substring(operatorStart + 1).trim()
        } else {
            ""
        }
        val startOffset = lineStartOffset + column
        val endOffset = startOffset + variableName.length
        val definition = VariableDefinition(
            name = variableName,
            value = value,
            line = lineIndex,
            column = column,
            endLine = lineIndex,
            endColumn = column + variableName.length,
            startOffset = startOffset,
            endOffset = endOffset
        )
        variables.putIfAbsent(variableName, definition)
        occurrences += SymbolOccurrence(
            name = variableName,
            symbolType = SymbolType.VARIABLE,
            role = SymbolRole.DEFINITION,
            startOffset = startOffset,
            endOffset = endOffset
        )
    }

    private fun collectTargetDefinitionsAndReferences(
        rawLine: String,
        trimmed: String,
        lineIndex: Int,
        lineStartOffset: Int,
        targets: MutableMap<String, TargetDefinition>,
        occurrences: MutableList<SymbolOccurrence>,
        phonyTargets: MutableSet<String>
    ) {
        val rawColonIndex = rawLine.indexOf(':')
        if (rawColonIndex <= 0) return
        val colonIndex = trimmed.indexOf(':')
        if (colonIndex <= 0) return
        val nextChar = trimmed.getOrNull(colonIndex + 1)
        if (nextChar == '=') return

        val targetPart = trimmed.substring(0, colonIndex).trim()
        val rawTargetPart = rawLine.substring(0, rawColonIndex)
        val rawPrerequisitePart = rawLine.substring(rawColonIndex + 1)
            .substringBefore(';')
            .trim()

        val targetTokens = targetPart.split(WHITESPACE_REGEX)
            .filter { it.isMeaningfulTargetToken() }
        if (targetTokens.isEmpty()) return

        val primaryTarget = targetTokens.first()
        val prerequisiteTokens = rawPrerequisitePart.split(WHITESPACE_REGEX)
            .filter { it.isMeaningfulTargetToken() }
            .filter { it != "|" }

        if (primaryTarget == ".PHONY") {
            phonyTargets += prerequisiteTokens
        }

        targetTokens.forEach { targetName ->
            val column = rawTargetPart.indexOf(targetName)
            if (column < 0 || targetName.startsWith('.')) return@forEach
            val startOffset = lineStartOffset + column
            val endOffset = startOffset + targetName.length
            val definition = TargetDefinition(
                name = targetName,
                prerequisites = prerequisiteTokens,
                line = lineIndex,
                column = column,
                startOffset = startOffset,
                endOffset = endOffset,
                phony = targetName in phonyTargets
            )
            targets.putIfAbsent(targetName, definition)
            occurrences += SymbolOccurrence(
                name = targetName,
                symbolType = SymbolType.TARGET,
                role = SymbolRole.DEFINITION,
                startOffset = startOffset,
                endOffset = endOffset
            )
        }

        prerequisiteTokens.forEach { dependency ->
            val dependencyColumn = rawLine.indexOf(dependency, rawColonIndex + 1)
            if (dependencyColumn < 0) return@forEach
            occurrences += SymbolOccurrence(
                name = dependency,
                symbolType = SymbolType.TARGET,
                role = SymbolRole.REFERENCE,
                startOffset = lineStartOffset + dependencyColumn,
                endOffset = lineStartOffset + dependencyColumn + dependency.length
            )
        }
    }

    private fun collectVariableReferences(
        rawLine: String,
        lineStartOffset: Int,
        occurrences: MutableList<SymbolOccurrence>
    ) {
        var index = 0
        while (index < rawLine.length - 1) {
            if (rawLine[index] != '$') {
                index++
                continue
            }

            val opener = rawLine[index + 1]
            if (opener != '(' && opener != '{') {
                index++
                continue
            }

            val closer = if (opener == '(') ')' else '}'
            val nameStart = index + 2
            val closeIndex = rawLine.indexOf(closer, nameStart)
            if (closeIndex < 0) {
                index++
                continue
            }

            var nameEnd = nameStart
            while (nameEnd < closeIndex && !rawLine[nameEnd].isWhitespace() && rawLine[nameEnd] != ',') {
                nameEnd++
            }
            if (nameEnd <= nameStart) {
                index = closeIndex + 1
                continue
            }

            val name = rawLine.substring(nameStart, nameEnd)
            val hasFunctionArguments = nameEnd < closeIndex && rawLine[nameEnd].isWhitespace()
            if (hasFunctionArguments && name in MAKEFILE_FUNCTIONS) {
                index = nameEnd + 1
                continue
            }

            occurrences += SymbolOccurrence(
                name = name,
                symbolType = SymbolType.VARIABLE,
                role = SymbolRole.REFERENCE,
                startOffset = lineStartOffset + nameStart,
                endOffset = lineStartOffset + nameEnd
            )
            index = closeIndex + 1
        }
    }

    private fun normalizeVariableDefinition(trimmed: String): String? {
        if (trimmed.startsWith("define ")) return null
        var current = trimmed
        var updated: Boolean
        do {
            updated = false
            PREFIX_QUALIFIERS.forEach { qualifier ->
                if (current.startsWith("$qualifier ")) {
                    current = current.substringAfter("$qualifier ").trimStart()
                    updated = true
                }
            }
        } while (updated)
        return current
    }

    private fun tokenAtOffset(source: String, offset: Int): String? {
        if (source.isEmpty()) return null
        automaticVariableAtOffset(source, offset)?.let { return it }
        val safeOffset = offset.coerceIn(0, source.length)
        var start = safeOffset.coerceAtMost(source.lastIndex)
        if (start > 0 && start == source.length) {
            start--
        }
        if (start !in source.indices || !isMakeTokenChar(source[start])) {
            if (start > 0 && isMakeTokenChar(source[start - 1])) {
                start--
            } else {
                return null
            }
        }
        var end = start
        while (start > 0 && isMakeTokenChar(source[start - 1])) {
            start--
        }
        while (end + 1 < source.length && isMakeTokenChar(source[end + 1])) {
            end++
        }
        return source.substring(start, end + 1)
    }

    private fun automaticVariableAtOffset(source: String, offset: Int): String? {
        if (source.length < 2) return null
        val safeOffset = offset.coerceIn(0, source.lastIndex)
        val candidateStarts = intArrayOf(safeOffset, safeOffset - 1)
        candidateStarts.forEach { start ->
            if (start < 0 || start + 1 >= source.length) return@forEach
            if (source[start] != '$') return@forEach
            val candidate = source.substring(start, start + 2)
            if (candidate in MAKEFILE_AUTOMATIC_VARIABLES) {
                return candidate
            }
        }
        return null
    }

    private fun String.isMeaningfulTargetToken(): Boolean = isNotBlank() &&
        !contains('$') &&
        !contains('%') &&
        this != "|" &&
        this != ";"

    private fun String.matchesMakePrefix(prefix: String, caseSensitive: Boolean): Boolean {
        if (prefix.isBlank()) return true
        val ignoreCase = !caseSensitive
        if (startsWith(prefix, ignoreCase = ignoreCase)) return true
        if (startsWith(".") && drop(1).startsWith(prefix, ignoreCase = ignoreCase)) return true
        return false
    }

    private fun buildDocumentedVariableHover(
        name: String,
        definition: VariableDefinition? = null
    ): String? {
        val builtinDoc = MAKEFILE_IMPLICIT_VARIABLE_DOCS[name]
        if (builtinDoc != null) {
            return buildDocumentedVariableHover(
                label = "Builtin variable",
                name = name,
                doc = builtinDoc,
                definition = definition
            )
        }
        val automaticDoc = MAKEFILE_AUTOMATIC_VARIABLE_DOCS[name]
        if (automaticDoc != null) {
            return buildDocumentedVariableHover(
                label = "Automatic variable",
                name = name,
                doc = automaticDoc,
                definition = definition
            )
        }
        return null
    }

    private fun buildDocumentedVariableHover(
        label: String,
        name: String,
        doc: String,
        definition: VariableDefinition?
    ): String = buildString {
        append("**")
        append(label)
        append("** `")
        append(name)
        append("`\n\n")
        append(doc)
        definition?.let { variable ->
            append("\n\n**Variable** value\n\nDefined at line ")
            append(variable.line + 1)
            if (variable.value.isNotBlank()) {
                append(".\n\n```make\n")
                append(variable.value)
                append("\n```")
            } else {
                append('.')
            }
        }
    }

    private fun buildVariableHover(definition: VariableDefinition): String = buildString {
        append("**Variable** `")
        append(definition.name)
        append("`\n\n")
        append("Defined at line ")
        append(definition.line + 1)
        if (definition.value.isNotBlank()) {
            append(".\n\n```make\n")
            append(definition.value)
            append("\n```")
        }
    }

    private fun isMakeCompletionPrefixChar(c: Char): Boolean = c == '$' || isMakeTokenChar(c)

    private fun isMakeTokenChar(c: Char): Boolean = c == '_' ||
        c == '.' ||
        c == '/' ||
        c == '-' ||
        c == '@' ||
        c == '<' ||
        c == '^' ||
        c == '?' ||
        c == '+' ||
        c == '*' ||
        c == '%' ||
        c.isLetterOrDigit()

    private data class MakeParsedDocument(
        val source: String,
        val variables: Map<String, VariableDefinition>,
        val targets: Map<String, TargetDefinition>,
        val occurrences: List<SymbolOccurrence>,
        val diagnostics: List<MakeDiagnostic>
    ) {
        fun occurrenceAt(offset: Int): SymbolOccurrence? = occurrences.firstOrNull {
            offset in it.startOffset until it.endOffset.coerceAtLeast(it.startOffset + 1)
        }
    }

    private data class VariableDefinition(
        val name: String,
        val value: String,
        val line: Int,
        val column: Int,
        val endLine: Int,
        val endColumn: Int,
        val startOffset: Int,
        val endOffset: Int
    )

    private data class TargetDefinition(
        val name: String,
        val prerequisites: List<String>,
        val line: Int,
        val column: Int,
        val startOffset: Int,
        val endOffset: Int,
        val phony: Boolean
    )

    private data class SymbolOccurrence(
        val name: String,
        val symbolType: SymbolType,
        val role: SymbolRole,
        val startOffset: Int,
        val endOffset: Int
    ) {
        fun toLocationItem(file: File, documentUri: String, mapper: OffsetMapper): LocationItem {
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

    private data class MakeDiagnostic(
        val startOffset: Int,
        val endOffset: Int,
        val message: String,
        val severity: Diagnostic.Severity
    )

    private data class BlockFrame(
        val type: BlockType,
        val keyword: String,
        val startOffset: Int,
        val endOffset: Int
    )

    private enum class SymbolType {
        VARIABLE,
        TARGET
    }

    private enum class SymbolRole {
        DEFINITION,
        REFERENCE
    }

    private enum class BlockType {
        CONDITIONAL,
        DEFINE
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

private val PREFIX_QUALIFIERS: Set<String> = linkedSetOf(
    "export",
    "override",
    "private",
    "unexport"
)

private val MAKEFILE_DIRECTIVES: Set<String> = linkedSetOf(
    "include", "define", "endef", "ifdef", "ifndef", "ifeq", "ifneq",
    "else", "endif", "export", "unexport", "override", "private", "vpath",
)

private val MAKEFILE_SPECIAL_TARGETS: Set<String> = linkedSetOf(
    ".PHONY", ".DEFAULT", ".SUFFIXES", ".PRECIOUS", ".INTERMEDIATE",
    ".SECONDARY", ".DELETE_ON_ERROR", ".NOTPARALLEL", ".ONESHELL",
    ".SILENT", ".IGNORE", ".LOW_RESOLUTION_TIME", ".EXPORT_ALL_VARIABLES",
    ".POSIX",
)

private val MAKEFILE_FUNCTIONS: Set<String> = linkedSetOf(
    "wildcard", "patsubst", "subst", "filter", "filter-out",
    "sort", "dir", "notdir", "basename", "suffix",
    "addsuffix", "addprefix", "word", "words", "firstword", "lastword",
    "foreach", "if", "or", "and", "call", "value",
    "eval", "shell", "error", "warning", "info",
    "join", "realpath", "abspath", "strip", "findstring",
    "flavor", "origin", "file",
)

private val MAKEFILE_IMPLICIT_VARIABLES: Set<String> = linkedSetOf(
    "CC", "CXX", "CFLAGS", "CXXFLAGS", "CPPFLAGS",
    "LDFLAGS", "LDLIBS", "AR", "ARFLAGS", "RM",
    "MAKE", "MAKEFLAGS", "MAKECMDGOALS", "CURDIR",
    "VPATH", "SHELL",
)

private val MAKEFILE_AUTOMATIC_VARIABLES: Set<String> = linkedSetOf(
    "${'$'}@",
    "${'$'}<",
    "${'$'}^",
    "${'$'}?",
    "${'$'}+",
    "${'$'}*",
    "${'$'}%"
)

private val MAKEFILE_KEYWORDS: Set<String> =
    MAKEFILE_DIRECTIVES + MAKEFILE_SPECIAL_TARGETS + MAKEFILE_FUNCTIONS + MAKEFILE_IMPLICIT_VARIABLES

private val MAKEFILE_VAR_REGEX = Regex("""^([A-Za-z_][A-Za-z0-9_]*)\s*[:?+!]?=.*""")
private val MAKEFILE_SIMPLE_VARIABLE_NAME = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
private val WHITESPACE_REGEX = Regex("""\s+""")

private val MAKEFILE_DIRECTIVE_DOCS: Map<String, String> = mapOf(
    "include" to "Loads another makefile before continuing evaluation.",
    "define" to "Starts a multi-line variable definition block.",
    "ifdef" to "Checks whether a variable is defined.",
    "ifndef" to "Checks whether a variable is not defined.",
    "ifeq" to "Checks whether two values are equal.",
    "ifneq" to "Checks whether two values are different.",
    "override" to "Forces an assignment to win over command-line overrides.",
    "export" to "Exports a variable to sub-make processes.",
)

private val MAKEFILE_FUNCTION_DOCS: Map<String, String> = mapOf(
    "wildcard" to "Expands file patterns using the current filesystem.",
    "patsubst" to "Performs pattern substitution across a word list.",
    "foreach" to "Iterates over a list and expands the body for each item.",
    "call" to "Invokes a user-defined make function with arguments.",
    "shell" to "Runs a shell command and captures its output.",
)

private val MAKEFILE_SPECIAL_TARGET_DOCS: Map<String, String> = mapOf(
    ".PHONY" to "Marks targets as always out-of-date so they always run.",
    ".ONESHELL" to "Runs all recipe lines for a target in a single shell.",
    ".DELETE_ON_ERROR" to "Deletes targets when a recipe fails partway through.",
)

private val MAKEFILE_IMPLICIT_VARIABLE_DOCS: Map<String, String> = mapOf(
    "CC" to "C compiler command used by implicit rules.",
    "CXX" to "C++ compiler command used by implicit rules.",
    "CFLAGS" to "Additional flags for the C compiler.",
    "CXXFLAGS" to "Additional flags for the C++ compiler.",
    "CPPFLAGS" to "Preprocessor flags shared by C and C++ compilation.",
    "LDFLAGS" to "Additional flags passed to the linker.",
    "LDLIBS" to "Libraries appended during link steps.",
    "MAKE" to "Path to the current make executable.",
    "MAKECMDGOALS" to "Command-line targets requested for this invocation.",
    "CURDIR" to "Absolute path of the current working directory.",
)

private val MAKEFILE_AUTOMATIC_VARIABLE_DOCS: Map<String, String> = mapOf(
    "${'$'}@" to "The target name of the current rule.",
    "${'$'}<" to "The first prerequisite of the current rule.",
    "${'$'}^" to "All prerequisites with duplicates removed.",
    "${'$'}?" to "Prerequisites newer than the target.",
    "${'$'}+" to "All prerequisites including duplicates.",
    "${'$'}*" to "The stem matched by the implicit rule.",
    "${'$'}%" to "The archive member name for archive targets.",
)
