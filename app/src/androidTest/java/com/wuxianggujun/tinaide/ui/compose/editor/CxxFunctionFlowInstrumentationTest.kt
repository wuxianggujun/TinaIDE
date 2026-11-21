package com.wuxianggujun.tinaide.ui.compose.editor

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wuxianggujun.tinaide.editor.analysis.CxxFunctionFlowAnalyzer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CxxFunctionFlowInstrumentationTest {
    @Test fun nativeParserKeepsLoopBranchesAndUnicodePositions() = runBlocking {
        val source = """
            // 中文
            int work(int count) {
                int sum = 0;
                for (int i = 0; i < count; ++i) {
                    if (i == 1) continue;
                    if (i == 9) break;
                    sum += i;
                }
                return sum;
            }
        """.trimIndent()
        val graph = CxxFunctionFlowAnalyzer().analyze(source, 5)
        val continueNode = graph.nodes.single { it.text == "continue;" }
        val update = graph.nodes.single { it.text == "++i" }
        assertEquals(4, continueNode.source?.line)
        assertTrue(graph.edges.any { it.from == continueNode.id && it.to == update.id })
        assertTrue(graph.signature.contains("work"))
    }

    @Test fun switchAndDoWhileUseNativeGrammar() = runBlocking {
        val graph = CxxFunctionFlowAnalyzer().analyze("""
            int f(int n) {
                do { --n; } while (n > 4);
                switch (n) {
                    case 1: ++n;
                    case 2: break;
                    default: return 0;
                }
                return n;
            }
        """.trimIndent(), 4)
        assertTrue(graph.edges.any { it.label == "case 1" })
        assertTrue(graph.edges.any { it.label == "default" })
        assertFalse(graph.nodes.isEmpty())
    }

    @Test fun unsupportedAndInvalidFunctionsReturnFailure() = runBlocking {
        listOf("void f() { goto finish; finish: return; }", "void f() { int x = ; }", "void f() { try {} catch(...) {} }").forEach { source ->
            val failure = runCatching { CxxFunctionFlowAnalyzer().analyze(source, 0) }.exceptionOrNull()
            assertTrue("Expected unsupported syntax to fail validation, got $failure", failure is IllegalArgumentException)
        }
    }
}
