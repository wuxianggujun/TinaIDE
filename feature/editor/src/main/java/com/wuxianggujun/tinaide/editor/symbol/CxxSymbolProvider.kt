package com.wuxianggujun.tinaide.editor.symbol

import com.itsaky.androidide.treesitter.TSLanguage
import com.itsaky.androidide.treesitter.TSNode
import com.itsaky.androidide.treesitter.cpp.TSLanguageCpp
import com.wuxianggujun.tinaide.core.lang.CxxFileSupport
import java.util.ArrayDeque

/** Syntax-level project symbols. Function bodies and initializers are not global scopes. */
internal class CxxSymbolProvider : LanguageSymbolProvider {
    override val supportedExtensions: Set<String> = CxxFileSupport.editorRelatedExtensions
    override fun createLanguage(): TSLanguage = TSLanguageCpp.getInstance()

    private data class Scope(val name: String? = null, val inType: Boolean = false, val namespace: String? = null)
    private data class Visit(val node: TSNode, val scope: Scope)
    private data class DeclaredName(val name: String, val node: TSNode, val qualifier: String? = null)

    override fun extractSymbols(root: TSNode, source: String): List<GlobalSymbol> {
        if (!root.isAccessible()) return emptyList()
        val symbols = mutableListOf<GlobalSymbol>()
        val typeScopes = mutableSetOf<String>()
        val lines = source.lines()
        val pending = ArrayDeque<Visit>()
        pending.addLast(Visit(root, Scope()))
        while (pending.isNotEmpty()) {
            val (node, scope) = pending.removeLast()
            if (!node.isAccessible()) continue
            when (node.type) {
                "namespace_definition", "class_specifier", "struct_specifier", "enum_specifier" -> {
                    val kind = when (node.type) {
                        "namespace_definition" -> SymbolKind.Namespace
                        "class_specifier" -> SymbolKind.Class
                        "struct_specifier" -> SymbolKind.Struct
                        else -> SymbolKind.Enum
                    }
                    val name = node.getChildByFieldName("name")?.let { declaredName(it, source) }
                    val container = name?.let { containerName(scope, it) } ?: scope.name
                    val qualifiedName = name?.let { qualify(container, it.name) } ?: scope.name
                    if (name != null) {
                        symbols += GlobalSymbol(
                            name = name.name,
                            kind = kind,
                            detail = kind.name.lowercase(),
                            location = locationOf(name.node),
                            documentation = extractDocComment(node, lines),
                            containerName = container,
                        )
                        if (kind == SymbolKind.Class || kind == SymbolKind.Struct) {
                            typeScopes += requireNotNull(qualifiedName)
                        }
                    }
                    node.getChildByFieldName("body")?.let { body ->
                        pending.addLast(Visit(body, Scope(
                            name = qualifiedName,
                            inType = scope.inType || kind != SymbolKind.Namespace,
                            namespace = if (kind == SymbolKind.Namespace) qualifiedName else scope.namespace,
                        )))
                    }
                }
                "function_definition" -> {
                    node.getChildByFieldName("declarator")?.let { declarator ->
                        extractDeclaration(node, declarator, scope, source, lines, isFunction = true)?.let(symbols::add)
                    }
                    // Do not descend into parameters, the body, local types or lambdas.
                }
                "declaration", "field_declaration" -> {
                    for (index in 0 until node.namedChildCount) {
                        val child = node.getNamedChild(index)
                        if (!child.isAccessible()) continue
                        when (child.type) {
                            "class_specifier", "struct_specifier", "enum_specifier" -> pending.addLast(Visit(child, scope))
                            else -> if (isDeclarator(child.type)) {
                                val declarator = if (child.type == "init_declarator") {
                                    child.getChildByFieldName("declarator") ?: continue
                                } else child
                                extractDeclaration(node, declarator, scope, source, lines, isFunctionDeclarator(declarator))
                                    ?.let(symbols::add)
                            }
                        }
                    }
                }
                "parameter_list", "parameter_declaration", "template_parameter_list",
                "compound_statement", "lambda_expression", "initializer_list", "argument_list" -> Unit
                "friend_declaration" -> {
                    for (index in node.namedChildCount - 1 downTo 0) {
                        pending.addLast(Visit(node.getNamedChild(index), Scope(scope.namespace, namespace = scope.namespace)))
                    }
                }
                else -> {
                    for (index in node.namedChildCount - 1 downTo 0) {
                        pending.addLast(Visit(node.getNamedChild(index), scope))
                    }
                }
            }
        }
        return symbols.asSequence()
            .map { symbol ->
                if (symbol.kind == SymbolKind.Function && symbol.containerName != null && symbol.containerName in typeScopes) {
                    symbol.copy(kind = SymbolKind.Method, detail = "method")
                } else symbol
            }
            .distinctBy { listOf(it.kind, it.containerName, it.name, it.signature, it.location) }
            .sortedWith(compareBy({ it.name }, { it.containerName.orEmpty() }, { it.location?.line ?: 0 }))
            .toList()
    }

    private fun extractDeclaration(
        declaration: TSNode,
        declarator: TSNode,
        scope: Scope,
        source: String,
        lines: List<String>,
        isFunction: Boolean,
    ): GlobalSymbol? {
        val name = declaredName(declarator, source) ?: return null
        val kind = when {
            isFunction && scope.inType -> SymbolKind.Method
            isFunction -> SymbolKind.Function
            scope.inType -> SymbolKind.Field
            else -> SymbolKind.Variable
        }
        val type = declaration.getChildByFieldName("type")?.let { nodeText(it, source) }
        val signature = listOfNotNull(type, nodeText(declarator, source)).joinToString(" ").ifBlank { null }
        return GlobalSymbol(
            name = name.name,
            kind = kind,
            detail = kind.name.lowercase(),
            location = locationOf(name.node),
            signature = signature,
            documentation = extractDocComment(declaration, lines),
            containerName = containerName(scope, name),
        )
    }

    private fun isDeclarator(type: String): Boolean = type.endsWith("declarator") || type in setOf(
        "identifier", "field_identifier", "qualified_identifier", "operator_name", "operator_cast",
        "destructor_name", "template_function", "template_method",
    )

    /** The binding nearest the name distinguishes a function from a function-pointer variable. */
    private fun isFunctionDeclarator(declarator: TSNode): Boolean {
        var node: TSNode? = declarator
        var functionBinding = false
        while (node != null && node.isAccessible()) {
            when (node.type) {
                "operator_cast" -> return true
                "function_declarator", "function_field_declarator", "abstract_function_declarator" -> functionBinding = true
                "pointer_declarator", "reference_declarator", "array_declarator" -> functionBinding = false
            }
            node = nextDeclarator(node)
        }
        return functionBinding
    }

    private fun declaredName(node: TSNode, source: String): DeclaredName? {
        if (!node.isAccessible()) return null
        return when (node.type) {
            "identifier", "field_identifier", "type_identifier", "namespace_identifier",
            "nested_namespace_specifier", "operator_name", "destructor_name" -> {
                nodeText(node, source)?.let { DeclaredName(it, node) }
            }
            "qualified_identifier" -> {
                val member = node.getChildByFieldName("name")?.takeIf { it.isAccessible() } ?: return null
                val name = declaredName(member, source) ?: return null
                val prefixText = source.substring(
                    (node.startByte / 2).coerceIn(0, source.length),
                    (member.startByte / 2).coerceIn(0, source.length),
                ).trim()
                val prefix = prefixText.removeSuffix("::").trim().ifBlank {
                    if (prefixText.startsWith("::")) "::" else ""
                }
                val qualifier = if (prefix == "::") "::" + name.qualifier.orEmpty()
                    else qualify(prefix, name.qualifier.orEmpty())
                name.copy(qualifier = qualifier)
            }
            "operator_cast" -> {
                val nested = node.getChildByFieldName("declarator") ?: return null
                val text = source.substring(node.startByte / 2, nested.startByte / 2).trim()
                text.takeIf { it.isNotEmpty() }?.let { DeclaredName(it, node) }
            }
            "template_function", "template_method", "template_type" -> {
                node.getChildByFieldName("name")?.let { declaredName(it, source) }
            }
            else -> nextDeclarator(node)?.let { declaredName(it, source) }
        }
    }

    private fun nextDeclarator(node: TSNode): TSNode? {
        node.getChildByFieldName("declarator")?.takeIf { it.isAccessible() }?.let { return it }
        if (node.type == "parenthesized_declarator" || node.type == "attributed_declarator") {
            return (0 until node.namedChildCount).asSequence()
                .map { node.getNamedChild(it) }
                .firstOrNull { it.isAccessible() && isDeclarator(it.type) }
        }
        return null
    }

    private fun containerName(scope: Scope, name: DeclaredName): String? {
        val qualifier = name.qualifier ?: return scope.name
        return if (qualifier.startsWith("::")) qualifier.removePrefix("::").ifBlank { null }
        else qualify(scope.name, qualifier).ifBlank { null }
    }

    private fun qualify(container: String?, name: String): String =
        listOfNotNull(container?.takeIf { it.isNotBlank() }, name.takeIf { it.isNotBlank() }).joinToString("::")

    private fun TSNode.isAccessible(): Boolean = canAccess() && !isNull

    private fun locationOf(node: TSNode): SymbolLocation = SymbolLocation(
        line = node.startPoint.row,
        column = node.startPoint.column / 2,
    )

    private fun nodeText(node: TSNode, source: String): String? {
        if (!node.isAccessible()) return null
        val start = (node.startByte / 2).coerceIn(0, source.length)
        val end = (node.endByte / 2).coerceIn(start, source.length)
        return source.substring(start, end).trim().takeIf { it.isNotEmpty() }
    }

    private fun extractDocComment(node: TSNode, lines: List<String>): String? {
        val comments = mutableListOf<String>()
        for (index in node.startPoint.row - 1 downTo maxOf(0, node.startPoint.row - 5)) {
            val line = lines.getOrNull(index)?.trim() ?: continue
            when {
                line.startsWith("//") -> comments.add(0, line.removePrefix("///").removePrefix("//").trim())
                line.startsWith("/*") && line.endsWith("*/") -> {
                    comments.add(0, line.removePrefix("/*").removeSuffix("*/").trim())
                    break
                }
                line.isEmpty() -> continue
                else -> break
            }
        }
        return comments.takeIf { it.isNotEmpty() }?.joinToString(" ")?.take(200)
    }
}
