package com.wuxianggujun.tinaide.core.treesitter

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class BundledTreeSitterQueryTest {

    @Test
    fun queries_shouldOnlyReferenceNodesFromBundledGrammars() {
        val queryRoot = locateDirectory(
            "src/main/assets/tree-sitter-queries",
            "core/tree-sitter/src/main/assets/tree-sitter-queries",
        )
        val grammarRoot = locateDirectory(
            "../../external/tina-android-tree-sitter/grammars",
            "external/tina-android-tree-sitter/grammars",
        )

        val unknownNodes = buildList {
            queryRoot.listFiles()
                .orEmpty()
                .filter(File::isDirectory)
                .forEach { languageDirectory ->
                    val nodeTypesFile = File(grammarRoot, "${languageDirectory.name}/src/node-types.json")
                    if (!nodeTypesFile.isFile) return@forEach
                    val knownTypes = extractNodeTypes(nodeTypesFile.readText(Charsets.UTF_8))
                    languageDirectory.listFiles { file -> file.extension == "scm" }
                        .orEmpty()
                        .forEach { queryFile ->
                            extractQueryNodeNames(queryFile.readText(Charsets.UTF_8))
                                .filterNot { nodeName -> nodeName == "_" || nodeName == "ERROR" || nodeName in knownTypes }
                                .forEach { nodeName ->
                                    add("${languageDirectory.name}/${queryFile.name}:$nodeName")
                                }
                        }
                }
        }

        assertThat(unknownNodes).isEmpty()
    }

    @Test
    fun luaPatternConversion_shouldPreserveQueryIntent() {
        assertThat(Regex(luaPatternToRegex("^%d+$")).matches("2048")).isTrue()
        assertThat(Regex(luaPatternToRegex("^[%u@][%u%d_]+$")).matches("@API_34")).isTrue()
        assertThat(Regex(luaPatternToRegex("^[%l_].*$")).matches("property_name")).isTrue()
    }

    @Test
    fun treeSitterRegexConversion_shouldSupportCaseInsensitivePrefix() {
        val regex = Regex(treeSitterRegexToKotlinRegex("\\c^(continue|break)$"))

        assertThat(regex.matches("CONTINUE")).isTrue()
        assertThat(regex.matches("Break")).isTrue()
    }

    private fun locateDirectory(vararg candidates: String): File = candidates
        .asSequence()
        .map(::File)
        .firstOrNull(File::isDirectory)
        ?: error("Missing directory: ${candidates.joinToString()}")

    private fun extractNodeTypes(nodeTypesJson: String): Set<String> = Regex(""""type"\s*:\s*"([^"]+)"""")
        .findAll(nodeTypesJson)
        .map { match -> match.groupValues[1] }
        .toSet()

    private fun extractQueryNodeNames(queryText: String): Set<String> {
        val syntaxOnly = stripQueryStringsAndComments(queryText)
        return Regex("""\(\s*([A-Za-z_][A-Za-z0-9_./-]*)""")
            .findAll(syntaxOnly)
            .map { match -> match.groupValues[1] }
            .toSet()
    }

    private fun stripQueryStringsAndComments(queryText: String): String = buildString(queryText.length) {
        var inString = false
        var inComment = false
        var escaped = false
        queryText.forEach { char ->
            when {
                inComment -> {
                    if (char == '\n') {
                        inComment = false
                        append(char)
                    } else {
                        append(' ')
                    }
                }
                inString -> {
                    when {
                        escaped -> escaped = false
                        char == '\\' -> escaped = true
                        char == '"' -> inString = false
                    }
                    append(' ')
                }
                char == ';' -> {
                    inComment = true
                    append(' ')
                }
                char == '"' -> {
                    inString = true
                    append(' ')
                }
                else -> append(char)
            }
        }
    }
}
