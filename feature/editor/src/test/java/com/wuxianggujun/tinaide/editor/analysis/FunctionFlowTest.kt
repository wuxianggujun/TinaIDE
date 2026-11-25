package com.wuxianggujun.tinaide.editor.analysis

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FunctionFlowTest {
    private fun action(text: String, line: Int = 1) = FlowStatement.Action(text, FlowSource(line, 0))

    @Test fun returnDoesNotFallThrough() {
        val graph = FunctionFlowBuilder().build("int f()", FlowStatement.Block(listOf(
            FlowStatement.Jump(action("return 1"), "return_statement"), action("unreachable"),
        )))
        val returning = graph.nodes.single { it.text == "return 1" }
        assertThat(graph.edges.single { it.from == returning.id }.to).isEqualTo(1)
    }

    @Test fun forContinueGoesToUpdateAndBreakGoesToFollowingStatement() {
        val graph = FunctionFlowBuilder().build("void f()", FlowStatement.Block(listOf(
            FlowStatement.Loop(action("i < 3"), FlowStatement.Branch(action("i == 1"),
                FlowStatement.Jump(action("continue"), "continue_statement"),
                FlowStatement.Jump(action("break"), "break_statement")), update = action("++i")),
            action("after"),
        )))
        fun id(text: String) = graph.nodes.single { it.text == text }.id
        assertThat(graph.edges).contains(FlowEdge(id("continue"), id("++i")))
        assertThat(graph.edges).contains(FlowEdge(id("break"), id("after")))
        assertThat(graph.edges).contains(FlowEdge(id("++i"), id("i < 3")))
    }

    @Test fun doWhileEntersBodyBeforeCondition() {
        val graph = FunctionFlowBuilder().build("f", FlowStatement.Loop(action("again"), action("body"), postTest = true))
        assertThat(graph.edges.single { it.from == 0 }.to).isEqualTo(graph.nodes.single { it.text == "body" }.id)
    }

    @Test fun switchFallthroughKeepsFollowingCaseAndDefault() {
        val graph = FunctionFlowBuilder().build("f", FlowStatement.Switch(action("value"), listOf(
            action("case 1") to FlowStatement.Block(listOf(action("first"))),
            action("default") to FlowStatement.Block(listOf(FlowStatement.Jump(action("break"), "break_statement"))),
        )))
        val first = graph.nodes.single { it.text == "first" }.id
        val default = graph.nodes.single { it.text == "default" }.id
        assertThat(graph.edges).contains(FlowEdge(first, default))
        assertThat(graph.edges.single { it.label == "default" }.to).isEqualTo(default)
    }

    @Test fun mermaidEscapesSourceAsLabelsAndLimitsNodes() {
        val graph = FunctionFlowBuilder().build("f", action("print(\"<x>\")"))
        assertThat(graph.mermaid()).contains("&quot;&lt;x&gt;&quot;")
        val oversized = runCatching { FunctionFlowBuilder().build("f", FlowStatement.Block(List(200) { action("a$it") })) }
        assertThat(oversized.isFailure).isTrue()
    }
}
