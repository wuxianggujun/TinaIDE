package com.wuxianggujun.tinaide.editor.analysis

import com.itsaky.androidide.treesitter.TSNode
import com.itsaky.androidide.treesitter.TSParser
import com.itsaky.androidide.treesitter.cpp.TSLanguageCpp
import com.wuxianggujun.tinaide.core.treesitter.TreeSitterRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CxxFunctionFlowAnalyzer {
    suspend fun analyze(source: String, line: Int): FunctionFlow = withContext(Dispatchers.Default) {
        require(source.length <= 512_000)
        TreeSitterRuntime.ensureInitialized()
        TSParser.create().use { parser ->
            parser.setLanguage(TSLanguageCpp.getInstance())
            parser.setTimeout(500_000)
            val tree = requireNotNull(parser.parseString(source))
            tree.use {
                val functions = mutableListOf<TSNode>()
                fun find(node: TSNode, depth: Int = 0) {
                    require(depth < 128)
                    if (!node.canAccess()) return
                    if (node.type == "function_definition" && line in node.startPoint.row..node.endPoint.row) functions += node
                    for (index in 0 until node.namedChildCount) {
                        val child = node.getNamedChild(index)
                        if (child.canAccess() && line in child.startPoint.row..child.endPoint.row) find(child, depth + 1)
                    }
                }
                find(tree.rootNode)
                val function = functions.minByOrNull { it.endByte - it.startByte } ?: error("No function at cursor")
                require(!function.hasErrors()) { "Invalid function syntax" }
                val body = requireNotNull(function.getChildByFieldName("body")?.takeIf { it.canAccess() && !it.isNull })
                val adapter = SyntaxAdapter(source)
                adapter.checkSupported(function)
                FunctionFlowBuilder().build(
                    source.substring(function.startByte / 2, body.startByte / 2).trim().take(240),
                    adapter.statement(body),
                )
            }
        }
    }

    private class SyntaxAdapter(private val source: String) {
        private fun text(node: TSNode) = source.substring(node.startByte / 2, node.endByte / 2).trim()
        private fun action(node: TSNode, label: String = text(node)) = FlowStatement.Action(label, FlowSource(node.startPoint.row, node.startPoint.column / 2))
        private fun children(node: TSNode) = (0 until node.namedChildCount)
            .map { node.getNamedChild(it) }
            .filter { it.canAccess() && it.type != "comment" }
        private fun optionalField(node: TSNode, name: String): TSNode? = node.getChildByFieldName(name)?.takeIf { it.canAccess() && !it.isNull }
        private fun field(node: TSNode, name: String) = requireNotNull(optionalField(node, name))

        fun checkSupported(node: TSNode, depth: Int = 0) {
            require(depth < 128)
            require(node.canAccess()) { "Inaccessible syntax node" }
            require(!node.isMissing) { "Incomplete syntax" }
            require(node.type != "condition_clause" || optionalField(node, "initializer") == null) { "Condition initializer not supported" }
            require(node.type !in setOf("ERROR", "goto_statement", "labeled_statement", "try_statement", "throw_expression", "lambda_expression", "for_range_loop", "co_await_expression", "co_yield_expression", "co_return_statement")) { "Unsupported syntax: ${node.type}" }
            require(!node.type.startsWith("preproc_")) { "Conditional preprocessing is not expanded" }
            children(node).forEach { checkSupported(it, depth + 1) }
        }

        fun statement(node: TSNode): FlowStatement = when (node.type) {
            "compound_statement" -> FlowStatement.Block(children(node).map(::statement))
            "else_clause" -> statement(children(node).last())
            "if_statement" -> {
                val condition = field(node, "condition")
                val initializer = optionalField(condition, "initializer")
                require(initializer == null) { "Condition initializer not supported" }
                FlowStatement.Branch(action(condition), statement(field(node, "consequence")), optionalField(node, "alternative")?.let(::statement))
            }
            "while_statement", "for_statement", "do_statement" -> FlowStatement.Loop(
                condition = optionalField(node, "condition")?.let(::action) ?: action(node, "true"),
                body = statement(field(node, "body")),
                initializer = optionalField(node, "initializer")?.let(::action),
                update = optionalField(node, "update")?.let(::action),
                postTest = node.type == "do_statement",
            )
            "switch_statement" -> {
                val cases = children(field(node, "body"))
                require(cases.all { it.type == "case_statement" }) { "Unsupported switch body" }
                FlowStatement.Switch(action(field(node, "condition")), cases.map { case ->
                    val value = optionalField(case, "value")
                    action(case, value?.let { "case ${text(it)}" } ?: "default") to
                        FlowStatement.Block(children(case).filter { value == null || it.startByte != value.startByte }.map(::statement))
                })
            }
            "return_statement", "break_statement", "continue_statement" -> FlowStatement.Jump(action(node), node.type)
            "expression_statement", "declaration", "type_definition", "static_assert_declaration", "null_statement" -> action(node)
            else -> error("Unsupported statement: ${node.type}")
        }
    }
}
