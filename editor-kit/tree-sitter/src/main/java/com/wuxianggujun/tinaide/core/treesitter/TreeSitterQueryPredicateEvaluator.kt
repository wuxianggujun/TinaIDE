package com.wuxianggujun.tinaide.core.treesitter

import com.itsaky.androidide.treesitter.TSNode
import com.itsaky.androidide.treesitter.TSQuery
import com.itsaky.androidide.treesitter.TSQueryMatch
import com.itsaky.androidide.treesitter.TSQueryPredicateStep
import timber.log.Timber

internal class TreeSitterQueryPredicateEvaluator(query: TSQuery) {
    data class Evaluation(
        val accepted: Boolean,
        val priority: Int = DEFAULT_HIGHLIGHT_PRIORITY,
    )

    private sealed interface Operand {
        data class Capture(val index: Int) : Operand
        data class Literal(val value: String) : Operand
    }

    private sealed interface Predicate {
        fun matches(context: MatchContext): Boolean
    }

    private data object RejectPredicate : Predicate {
        override fun matches(context: MatchContext): Boolean = false
    }

    private data class TextSetPredicate(
        val captureIndex: Int,
        val expected: List<Operand>,
        val mode: TextSetMode,
    ) : Predicate {
        override fun matches(context: MatchContext): Boolean {
            val captured = context.texts(captureIndex)
            if (captured.isEmpty()) return false
            val expectedValues = expected.flatMap(context::texts)
            if (expectedValues.isEmpty()) return false
            return captured.all { text ->
                when (mode) {
                    TextSetMode.EQUALS -> text in expectedValues
                    TextSetMode.NOT_EQUALS -> text !in expectedValues
                    TextSetMode.CONTAINS -> expectedValues.any(text::contains)
                }
            }
        }
    }

    private data class RegexPredicate(
        val captureIndex: Int,
        val regex: Regex,
        val negate: Boolean,
    ) : Predicate {
        override fun matches(context: MatchContext): Boolean {
            val captured = context.texts(captureIndex)
            if (captured.isEmpty()) return false
            return captured.all { text -> regex.containsMatchIn(text) != negate }
        }
    }

    private data class StructuralPredicate(
        val captureIndex: Int,
        val expectedTypes: Set<String>,
        val relation: StructuralRelation,
        val negate: Boolean,
    ) : Predicate {
        override fun matches(context: MatchContext): Boolean {
            val nodes = context.nodes(captureIndex)
            if (nodes.isEmpty()) return false
            return nodes.all { node ->
                val found = when (relation) {
                    StructuralRelation.PARENT -> node.parentTypeOrNull() in expectedTypes
                    StructuralRelation.ANCESTOR -> node.hasAncestorType(expectedTypes)
                }
                found != negate
            }
        }
    }

    private data class PatternProgram(
        val predicates: List<Predicate>,
        val priority: Int,
    )

    private enum class TextSetMode {
        EQUALS,
        NOT_EQUALS,
        CONTAINS,
    }

    private enum class StructuralRelation {
        PARENT,
        ANCESTOR,
    }

    private class MatchContext(
        private val match: TSQueryMatch,
        private val sourceText: String,
    ) {
        private val textsByCaptureIndex = mutableMapOf<Int, List<String>>()
        private val nodesByCaptureIndex = mutableMapOf<Int, List<TSNode>>()

        fun texts(operand: Operand): List<String> = when (operand) {
            is Operand.Capture -> texts(operand.index)
            is Operand.Literal -> listOf(operand.value)
        }

        fun texts(captureIndex: Int): List<String> = textsByCaptureIndex.getOrPut(captureIndex) {
            nodes(captureIndex).mapNotNull { node -> node.textFrom(sourceText) }
        }

        fun nodes(captureIndex: Int): List<TSNode> = nodesByCaptureIndex.getOrPut(captureIndex) {
            match.captures
                .asSequence()
                .filter { capture -> capture.index == captureIndex }
                .map { capture -> capture.node }
                .toList()
        }
    }

    private val captureIndexByName = query.captureNames
        .withIndex()
        .associate { (index, name) -> name to index }

    private val programs = Array(query.patternCount) { patternIndex ->
        compilePattern(query, patternIndex)
    }

    fun evaluate(match: TSQueryMatch, sourceText: String): Evaluation {
        val program = programs.getOrNull(match.patternIndex)
            ?: return Evaluation(accepted = false)
        if (program.predicates.isEmpty()) {
            return Evaluation(accepted = true, priority = program.priority)
        }

        val context = MatchContext(match, sourceText)
        return Evaluation(
            accepted = program.predicates.all { predicate -> predicate.matches(context) },
            priority = program.priority,
        )
    }

    private fun compilePattern(query: TSQuery, patternIndex: Int): PatternProgram {
        val predicates = mutableListOf<Predicate>()
        var priority = DEFAULT_HIGHLIGHT_PRIORITY
        splitPredicateSteps(query, patternIndex).forEach { steps ->
            val predicateName = (steps.firstOrNull() as? Operand.Literal)?.value ?: return@forEach
            val arguments = steps.drop(1)
            when (predicateName) {
                "set!" -> {
                    val key = (arguments.getOrNull(0) as? Operand.Literal)?.value
                    val value = (arguments.getOrNull(1) as? Operand.Literal)?.value
                    if (key == "priority") {
                        priority = value?.toIntOrNull() ?: priority
                    }
                }
                "eq?", "any-of?" -> predicates +=
                    compileTextSetPredicate(arguments, TextSetMode.EQUALS) ?: RejectPredicate
                "not-eq?", "not-any-of?" ->
                    predicates += compileTextSetPredicate(arguments, TextSetMode.NOT_EQUALS) ?: RejectPredicate
                "contains?" -> predicates +=
                    compileTextSetPredicate(arguments, TextSetMode.CONTAINS) ?: RejectPredicate
                "match?", "not-match?" ->
                    predicates += compileRegexPredicate(
                        arguments,
                        luaPattern = false,
                        negate = predicateName.startsWith("not-"),
                    ) ?: RejectPredicate
                "lua-match?", "not-lua-match?" ->
                    predicates += compileRegexPredicate(
                        arguments,
                        luaPattern = true,
                        negate = predicateName.startsWith("not-"),
                    ) ?: RejectPredicate
                "has-parent?", "not-has-parent?" ->
                    predicates += compileStructuralPredicate(
                        arguments = arguments,
                        relation = StructuralRelation.PARENT,
                        negate = predicateName.startsWith("not-"),
                    ) ?: RejectPredicate
                "has-ancestor?", "not-has-ancestor?" ->
                    predicates += compileStructuralPredicate(
                        arguments = arguments,
                        relation = StructuralRelation.ANCESTOR,
                        negate = predicateName.startsWith("not-"),
                    ) ?: RejectPredicate
                else -> {
                    Timber.tag("TreeSitter").w("Unsupported highlight query predicate: %s", predicateName)
                    predicates += RejectPredicate
                }
            }
        }
        return PatternProgram(predicates = predicates, priority = priority)
    }

    private fun splitPredicateSteps(query: TSQuery, patternIndex: Int): List<List<Operand>> {
        val groups = mutableListOf<List<Operand>>()
        var current = mutableListOf<Operand>()
        query.getPredicatesForPattern(patternIndex).forEach { step ->
            when (step.type) {
                TSQueryPredicateStep.Type.Capture -> {
                    val captureName = query.getCaptureNameForId(step.valueId)
                    captureIndexByName[captureName]?.let { index -> current += Operand.Capture(index) }
                }
                TSQueryPredicateStep.Type.String -> current += Operand.Literal(query.getStringValueForId(step.valueId))
                TSQueryPredicateStep.Type.Done -> {
                    if (current.isNotEmpty()) groups += current
                    current = mutableListOf()
                }
                null -> Unit
            }
        }
        if (current.isNotEmpty()) groups += current
        return groups
    }

    private fun compileTextSetPredicate(arguments: List<Operand>, mode: TextSetMode): Predicate? {
        val captureIndex = (arguments.firstOrNull() as? Operand.Capture)?.index ?: return null
        val expected = arguments.drop(1)
        if (expected.isEmpty()) return null
        return TextSetPredicate(captureIndex = captureIndex, expected = expected, mode = mode)
    }

    private fun compileRegexPredicate(
        arguments: List<Operand>,
        luaPattern: Boolean,
        negate: Boolean,
    ): Predicate? {
        val captureIndex = (arguments.getOrNull(0) as? Operand.Capture)?.index ?: return null
        val rawPattern = (arguments.getOrNull(1) as? Operand.Literal)?.value ?: return null
        val regexPattern = if (luaPattern) {
            luaPatternToRegex(rawPattern)
        } else {
            treeSitterRegexToKotlinRegex(rawPattern)
        }
        val regex = runCatching { Regex(regexPattern, RegexOption.DOT_MATCHES_ALL) }
            .onFailure { error ->
                Timber.tag("TreeSitter").w(error, "Invalid highlight query regex: %s", rawPattern)
            }
            .getOrNull() ?: return null
        return RegexPredicate(captureIndex = captureIndex, regex = regex, negate = negate)
    }

    private fun compileStructuralPredicate(
        arguments: List<Operand>,
        relation: StructuralRelation,
        negate: Boolean,
    ): Predicate? {
        val captureIndex = (arguments.firstOrNull() as? Operand.Capture)?.index ?: return null
        val expectedTypes = arguments.drop(1)
            .mapNotNull { operand -> (operand as? Operand.Literal)?.value }
            .toSet()
        if (expectedTypes.isEmpty()) return null
        return StructuralPredicate(
            captureIndex = captureIndex,
            expectedTypes = expectedTypes,
            relation = relation,
            negate = negate,
        )
    }
}

internal fun luaPatternToRegex(pattern: String): String = pattern
    .replace("%a", "[A-Za-z]")
    .replace("%d", "\\d")
    .replace("%l", "[a-z]")
    .replace("%s", "\\s")
    .replace("%u", "[A-Z]")
    .replace("%w", "[A-Za-z0-9]")
    .replace("%x", "[A-Fa-f0-9]")

internal fun treeSitterRegexToKotlinRegex(pattern: String): String = if (pattern.startsWith("\\c")) {
    "(?i)${pattern.removePrefix("\\c")}"
} else {
    pattern
}

private fun TSNode.textFrom(sourceText: String): String? {
    val start = startByte shr 1
    val end = endByte shr 1
    if (start !in 0..sourceText.length || end !in start..sourceText.length) return null
    return sourceText.substring(start, end)
}

private fun TSNode.parentTypeOrNull(): String? {
    val parentNode = runCatching { parent }.getOrNull() ?: return null
    if (!parentNode.canAccess() || parentNode.isNull) return null
    return runCatching { parentNode.type }.getOrNull()
}

private fun TSNode.hasAncestorType(expectedTypes: Set<String>): Boolean {
    var current = runCatching { parent }.getOrNull()
    var depth = 0
    while (current != null && current.canAccess() && !current.isNull && depth < 512) {
        if (runCatching { current.type }.getOrNull() in expectedTypes) return true
        current = runCatching { current.parent }.getOrNull()
        depth += 1
    }
    return false
}
