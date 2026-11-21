package com.wuxianggujun.tinaide.editor.analysis

/** A bounded, syntax-level graph. Each node retains a source location for navigation. */
data class FlowSource(val line: Int, val column: Int)
data class FlowNode(val id: Int, val text: String, val source: FlowSource?, val decision: Boolean = false)
data class FlowEdge(val from: Int, val to: Int, val label: String = "")
data class FunctionFlow(val signature: String, val nodes: List<FlowNode>, val edges: List<FlowEdge>) {
    fun mermaid(): String = buildString {
        appendLine("flowchart TD")
        nodes.forEach { node ->
            val text = node.text.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;").replace("\n", " ")
                .replace("`", "&#96;")
            val delimiters = if (node.decision) "{" to "}" else "[" to "]"
            appendLine("  n${node.id}${delimiters.first}\"$text\"${delimiters.second}")
        }
        edges.forEach { edge ->
            val label = edge.label.replace("&", "&amp;").replace("\"", "&quot;").replace("|", "&#124;")
            appendLine("  n${edge.from} -->${if (label.isEmpty()) "" else "|\"$label\"|"} n${edge.to}")
        }
    }
}

internal sealed interface FlowStatement {
    data class Block(val statements: List<FlowStatement>) : FlowStatement
    data class Action(val text: String, val source: FlowSource) : FlowStatement
    data class Branch(val condition: Action, val yes: FlowStatement, val no: FlowStatement?) : FlowStatement
    data class Loop(val condition: Action, val body: FlowStatement, val initializer: Action? = null, val update: Action? = null, val postTest: Boolean = false) : FlowStatement
    data class Switch(val condition: Action, val cases: List<Pair<Action, Block>>) : FlowStatement
    data class Jump(val action: Action, val kind: String) : FlowStatement
}

internal class FunctionFlowBuilder {
    private val nodes = mutableListOf<FlowNode>()
    private val edges = mutableListOf<FlowEdge>()
    private fun node(text: String, source: FlowSource? = null, decision: Boolean = false): Int {
        require(nodes.size < 180) { "Flow graph exceeds node limit" }
        return nodes.size.also { nodes += FlowNode(it, text.take(240), source, decision) }
    }
    private fun edge(from: Int, to: Int, label: String = "") { edges += FlowEdge(from, to, label) }

    fun build(signature: String, body: FlowStatement): FunctionFlow {
        val entry = node(signature)
        val exit = node("return / exit")
        edge(entry, emit(body, exit, exit, null, null))
        return FunctionFlow(signature, nodes.toList(), edges.distinct())
    }

    private fun emit(statement: FlowStatement, next: Int, exit: Int, breakTo: Int?, continueTo: Int?): Int = when (statement) {
        is FlowStatement.Block -> statement.statements.asReversed().fold(next) { tail, child -> emit(child, tail, exit, breakTo, continueTo) }
        is FlowStatement.Action -> node(statement.text, statement.source).also { edge(it, next) }
        is FlowStatement.Jump -> node(statement.action.text, statement.action.source).also {
            edge(it, when (statement.kind) {
                "return_statement" -> exit
                "break_statement" -> requireNotNull(breakTo)
                "continue_statement" -> requireNotNull(continueTo)
                else -> error("Unsupported jump")
            })
        }
        is FlowStatement.Branch -> node(statement.condition.text, statement.condition.source, true).also {
            edge(it, emit(statement.yes, next, exit, breakTo, continueTo), "true")
            edge(it, statement.no?.let { branch -> emit(branch, next, exit, breakTo, continueTo) } ?: next, "false")
        }
        is FlowStatement.Loop -> {
            val condition = node(statement.condition.text, statement.condition.source, true)
            val update = statement.update?.let { emit(it, condition, exit, next, condition) } ?: condition
            val body = emit(statement.body, update, exit, next, update)
            edge(condition, body, "true")
            // A missing for-condition means true, and has no false exit.
            if (statement.condition.text != "true") edge(condition, next, "false")
            val start = if (statement.postTest) body else condition
            statement.initializer?.let { emit(it, start, exit, breakTo, continueTo) } ?: start
        }
        is FlowStatement.Switch -> {
            val selector = node(statement.condition.text, statement.condition.source, true)
            var tail = next
            statement.cases.asReversed().forEach { (label, block) ->
                tail = emit(block, tail, exit, next, continueTo)
                val labelNode = node(label.text, label.source)
                edge(labelNode, tail)
                tail = labelNode
                edge(selector, labelNode, label.text)
            }
            if (statement.cases.none { it.first.text == "default" }) edge(selector, next, "default")
            selector
        }
    }
}
